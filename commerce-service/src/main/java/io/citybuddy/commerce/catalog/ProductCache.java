package io.citybuddy.commerce.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class ProductCache {
  private static final Logger LOGGER = LoggerFactory.getLogger(ProductCache.class);
  private static final long BLOOM_BITS = 65_536;
  private static final DefaultRedisScript<Long> RELEASE_LOCK =
      new DefaultRedisScript<>(
          "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
          Long.class);
  private static final DefaultRedisScript<Long> COMPLETE_BLOOM_NEGATIVE =
      new DefaultRedisScript<>(
          """
          if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end
          if redis.call('get', KEYS[2]) ~= 'true' then return 0 end
          if redis.call('exists', KEYS[3]) ~= 1 then return 0 end
          for index = 2, #ARGV do
            if redis.call('getbit', KEYS[3], ARGV[index]) == 1 then return 0 end
          end
          return 1
          """,
          Long.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final CatalogProperties properties;

  public ProductCache(
      StringRedisTemplate redis, ObjectMapper objectMapper, CatalogProperties properties) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  public Optional<Product> resolve(
      String productId, long generation, Supplier<Optional<Product>> mysqlLoader) {
    try {
      Optional<CachedLookup> cached = cached(productId, generation);
      if (cached.isPresent()) {
        return cached.get().product();
      }
      if (isCompleteBloomNegative(productId, generation)) {
        putNull(productId, generation);
        return Optional.empty();
      }
      return rebuildWithMutex(productId, generation, mysqlLoader);
    } catch (DataAccessException exception) {
      LOGGER.warn("Commerce Redis cache unavailable; reading product from MySQL", exception);
      return mysqlLoader.get();
    }
  }

  public void put(Product product, long generation) {
    String key = productKey(product.productId(), generation);
    redis.opsForValue().set(key, serialize(product), jitteredTtl(product));
    redis.opsForSet().add(productKeyIndex(product.productId()), key);
    redis.expire(
        productKeyIndex(product.productId()), properties.cacheTtl().plus(properties.cacheJitter()));
    redis.delete(nullKey(product.productId(), generation));
  }

  public void evict(String productId) {
    String index = productKeyIndex(productId);
    var keys = redis.opsForSet().members(index);
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
    redis.delete(index);
  }

  public void evict(String productId, long generation) {
    redis.delete(List.of(productKey(productId, generation), nullKey(productId, generation)));
  }

  public void rebuildBloom(long generation, List<String> publishedIds) {
    String bits = bloomBitsKey(generation);
    redis.opsForValue().set("catalog:bloom:complete", "false");
    redis.delete(bits);
    redis.opsForValue().setBit(bits, BLOOM_BITS - 1, false);
    for (String productId : publishedIds) {
      for (long offset : bloomOffsets(productId)) {
        redis.opsForValue().setBit(bits, offset, true);
      }
    }
    redis.opsForValue().set("catalog:bloom:generation", Long.toString(generation));
    redis.opsForValue().set("catalog:bloom:complete", "true");
  }

  private Optional<Product> rebuildWithMutex(
      String productId, long generation, Supplier<Optional<Product>> mysqlLoader) {
    String lockKey = "catalog:mutex:" + productId + ":" + generation;
    long waitNanos = Math.min(properties.mutexTtl().toNanos(), Duration.ofSeconds(1).toNanos());
    long deadline = System.nanoTime() + waitNanos;
    int attempt = 0;
    while (true) {
      Optional<Product> rebuilt = tryRebuild(lockKey, productId, generation, mysqlLoader);
      if (rebuilt != null) {
        return rebuilt;
      }
      Optional<CachedLookup> completed = cached(productId, generation);
      if (completed.isPresent()) {
        return completed.get().product();
      }
      if (System.nanoTime() >= deadline) {
        return mysqlLoader.get();
      }
      long ceilingMillis = Math.min(50L, 5L << Math.min(attempt++, 3));
      long delayMillis = ThreadLocalRandom.current().nextLong(1L, ceilingMillis + 1L);
      LockSupport.parkNanos(Duration.ofMillis(delayMillis).toNanos());
      if (Thread.currentThread().isInterrupted()) {
        return mysqlLoader.get();
      }
    }
  }

  private Optional<Product> tryRebuild(
      String lockKey, String productId, long generation, Supplier<Optional<Product>> mysqlLoader) {
    String token = UUID.randomUUID().toString();
    Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token, properties.mutexTtl());
    if (!Boolean.TRUE.equals(acquired)) {
      return null;
    }
    try {
      Optional<CachedLookup> secondRead = cached(productId, generation);
      if (secondRead.isPresent()) {
        return secondRead.get().product();
      }
      Optional<Product> loaded = mysqlLoader.get();
      if (loaded.isPresent()) {
        put(loaded.get(), generation);
      } else {
        putNull(productId, generation);
      }
      return loaded;
    } finally {
      redis.execute(RELEASE_LOCK, List.of(lockKey), token);
    }
  }

  private Optional<CachedLookup> cached(String productId, long generation) {
    String serialized = redis.opsForValue().get(productKey(productId, generation));
    if (serialized != null) {
      try {
        return Optional.of(
            new CachedLookup(Optional.of(objectMapper.readValue(serialized, Product.class))));
      } catch (JsonProcessingException exception) {
        redis.delete(productKey(productId, generation));
        LOGGER.warn("Discarded malformed product cache entry for {}", productId, exception);
      }
    }
    if (Boolean.TRUE.equals(redis.hasKey(nullKey(productId, generation)))) {
      return Optional.of(new CachedLookup(Optional.empty()));
    }
    return Optional.empty();
  }

  private boolean isCompleteBloomNegative(String productId, long generation) {
    String bits = bloomBitsKey(generation);
    long[] offsets = bloomOffsets(productId);
    Long result =
        redis.execute(
            COMPLETE_BLOOM_NEGATIVE,
            List.of("catalog:bloom:generation", "catalog:bloom:complete", bits),
            Long.toString(generation),
            Long.toString(offsets[0]),
            Long.toString(offsets[1]),
            Long.toString(offsets[2]),
            Long.toString(offsets[3]));
    return Long.valueOf(1L).equals(result);
  }

  private void putNull(String productId, long generation) {
    redis.opsForValue().set(nullKey(productId, generation), "1", properties.nullTtl());
  }

  private Duration jitteredTtl(Product product) {
    long jitterBound = properties.cacheJitter().toMillis();
    long jitter =
        jitterBound == 0
            ? 0
            : Math.floorMod(
                31L * product.productId().hashCode() + product.publicationVersion(),
                jitterBound + 1);
    return properties.cacheTtl().plusMillis(jitter);
  }

  private String serialize(Product product) {
    try {
      return objectMapper.writeValueAsString(product);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Product cache serialization failed", exception);
    }
  }

  private static long[] bloomOffsets(String productId) {
    long first = Integer.toUnsignedLong(productId.hashCode());
    long second =
        Integer.toUnsignedLong(new StringBuilder(productId).reverse().toString().hashCode());
    second = second == 0 ? 0x9e3779b9L : second;
    return new long[] {
      Math.floorMod(first, BLOOM_BITS),
      Math.floorMod(first + second, BLOOM_BITS),
      Math.floorMod(first + 2 * second, BLOOM_BITS),
      Math.floorMod(first + 3 * second, BLOOM_BITS)
    };
  }

  private static String productPrefix(String productId) {
    return "catalog:product:" + productId + ":";
  }

  private static String productKey(String productId, long generation) {
    return productPrefix(productId) + generation;
  }

  private static String productKeyIndex(String productId) {
    return "catalog:product-keys:" + productId;
  }

  private static String nullKey(String productId, long generation) {
    return "catalog:null:" + productId + ":" + generation;
  }

  private static String bloomBitsKey(long generation) {
    return "catalog:bloom:bits:" + generation;
  }

  private record CachedLookup(Optional<Product> product) {}
}
