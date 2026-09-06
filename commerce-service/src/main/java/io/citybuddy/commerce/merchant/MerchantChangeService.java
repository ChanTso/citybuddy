package io.citybuddy.commerce.merchant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.citybuddy.commerce.merchant.MerchantChangeModels.Command;
import io.citybuddy.commerce.merchant.MerchantChangeModels.Stored;
import io.citybuddy.commerce.merchant.MerchantChangeModels.View;
import io.citybuddy.commerce.merchant.MerchantModels.Context;
import io.citybuddy.commerce.merchant.MerchantModels.PrepareCommand;
import io.citybuddy.commerce.merchant.MerchantModels.PriceInput;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class MerchantChangeService {
  private static final Set<String> STATES = Set.of("PREPARED", "APPLIED", "CANCELLED", "REJECTED");
  private final MerchantChangeRepository repository;
  private final MerchantService prices;
  private final MerchantProductOperations products;
  private final MerchantMarketingOperations marketing;
  private final ObjectMapper mapper;
  private final Clock clock;

  public MerchantChangeService(
      MerchantChangeRepository repository,
      MerchantService prices,
      MerchantProductOperations products,
      MerchantMarketingOperations marketing,
      ObjectMapper mapper,
      Clock clock) {
    this.repository = repository;
    this.prices = prices;
    this.products = products;
    this.marketing = marketing;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public View prepare(Context context, String key, Command command) {
    MerchantService.requireText(key, "Idempotency-Key", 128);
    if (command == null
        || command.kind() == null
        || command.payload() == null
        || !command.payload().isObject()) {
      throw MerchantService.invalid("A change requires kind and an object payload");
    }
    String kind = command.kind();
    if (kind.equals("PRICE_UPDATE")) {
      var prepared = prices.prepare(context, key, priceCommand(command.payload()));
      return repository.find(prepared.draftId(), false).orElseThrow().view();
    }
    if (!Set.of("LISTING_UPDATE", "INVENTORY_ACTION", "PROMOTION", "CAMPAIGN").contains(kind)) {
      throw MerchantService.invalid("Unsupported merchant change kind");
    }
    String hash = intentHash(command);
    var existing = repository.findByRequest(context, key);
    if (existing.isPresent()) {
      return replay(existing.get(), kind, hash);
    }
    MerchantMarketingModels.PreparedOperation operation;
    try {
      if (Set.of("PROMOTION", "CAMPAIGN").contains(kind)) {
        operation = marketing.prepare(kind, command.payload());
      } else {
        var productOperation = products.prepare(kind, command.payload());
        operation =
            new MerchantMarketingModels.PreparedOperation(
                productOperation.currency(), productOperation.items(), productOperation.snapshot());
      }
    } catch (MerchantException rejected) {
      // The competing request may have committed its immutable proposal after our first read.
      return repository
          .findByRequest(context, key)
          .map(stored -> replay(stored, kind, hash))
          .orElseThrow(() -> rejected);
    }
    ObjectNode envelope = mapper.createObjectNode();
    envelope.set("request", command.payload());
    envelope.set("operation", operation.snapshot());
    Stored stored =
        repository.insertOrReplay(
            UUID.randomUUID().toString(),
            context,
            key,
            hash,
            kind,
            operation.currency(),
            operation.items(),
            envelope,
            clock.instant());
    return replay(stored, kind, hash);
  }

  public List<View> list(Context context, String state, int limit, int offset) {
    if ((state != null && !STATES.contains(state))
        || limit < 1
        || limit > 100
        || offset < 0
        || offset > 10000) {
      throw MerchantService.invalid("Invalid merchant change list filter");
    }
    return repository.list(context, state, limit, offset);
  }

  public View get(Context context, String id) {
    return owned(context, id, false).view();
  }

  @Transactional
  public View cancel(Context context, String id) {
    Stored stored = owned(context, id, true);
    if (!stored.view().state().equals("PREPARED")) {
      return stored.view();
    }
    return repository.resolve(
        id, "CANCELLED", mapper.valueToTree(Map.of("status", "CANCELLED")), clock.instant());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public View apply(String operator, String id) {
    Stored stored = repository.find(id, true).orElseThrow(MerchantChangeService::notFound);
    if (!stored.operatorSubject().equals(operator)) {
      throw notFound();
    }
    if (!stored.view().state().equals("PREPARED")) {
      return stored.view();
    }
    if (stored.view().kind().equals("PRICE_UPDATE")) {
      prices.apply(operator, id);
      return repository.find(id, true).orElseThrow().view();
    }
    try {
      JsonNode changes =
          Set.of("PROMOTION", "CAMPAIGN").contains(stored.view().kind())
              ? marketing.apply(stored.view().kind(), stored.snapshot(), id)
              : products.apply(stored.view().kind(), stored.snapshot());
      return repository.resolve(
          id,
          "APPLIED",
          mapper.valueToTree(Map.of("status", "APPLIED", "operation", changes)),
          clock.instant());
    } catch (MerchantProductOperationException rejected) {
      return repository.resolve(
          id,
          "REJECTED",
          mapper.valueToTree(
              Map.of(
                  "status",
                  "REJECTED",
                  "reason",
                  rejected.reason(),
                  "targetId",
                  rejected.targetId())),
          clock.instant());
    }
  }

  private Stored owned(Context context, String id, boolean lock) {
    Stored stored = repository.find(id, lock).orElseThrow(MerchantChangeService::notFound);
    if (!stored.operatorSubject().equals(context.operatorSubject())
        || !stored.sessionId().equals(context.sessionId())) {
      throw notFound();
    }
    return stored;
  }

  private static View replay(Stored stored, String kind, String hash) {
    if (!stored.view().kind().equals(kind) || !stored.intentHash().equals(hash)) {
      throw new MerchantException(
          409, "IDEMPOTENCY_CONFLICT", "Key already names another proposal");
    }
    return stored.view();
  }

  private PrepareCommand priceCommand(JsonNode payload) {
    requireFields(payload, Set.of("currency", "items"));
    if (!payload.get("currency").isTextual() || !payload.get("items").isArray()) {
      throw MerchantService.invalid("Invalid price change payload");
    }
    List<PriceInput> inputs = new ArrayList<>();
    for (JsonNode item : payload.get("items")) {
      requireFields(item, Set.of("productId", "newPriceMinor"));
      if (!item.get("productId").isTextual()
          || !item.get("newPriceMinor").isIntegralNumber()
          || !item.get("newPriceMinor").canConvertToLong()) {
        throw MerchantService.invalid("Invalid price change item");
      }
      inputs.add(
          new PriceInput(item.get("productId").textValue(), item.get("newPriceMinor").longValue()));
    }
    return new PrepareCommand(payload.get("currency").textValue(), inputs);
  }

  private static void requireFields(JsonNode value, Set<String> fields) {
    if (!value.isObject()
        || value.size() != fields.size()
        || fields.stream().anyMatch(field -> !value.hasNonNull(field))) {
      throw MerchantService.invalid("Unexpected or missing price change fields");
    }
  }

  private String intentHash(Command command) {
    try {
      // Canonicalize object keys, including nested listing fields; preserve meaningful array order.
      byte[] encoded =
          mapper
              .writer()
              .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .writeValueAsBytes(
                  mapper.convertValue(
                      Map.of("kind", command.kind(), "payload", command.payload()), Object.class));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
    } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Cannot encode merchant change intent", exception);
    }
  }

  private static MerchantException notFound() {
    return new MerchantException(404, "NOT_FOUND", "Change not found");
  }
}
