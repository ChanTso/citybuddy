package io.citybuddy.commerce.merchant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.catalog.ProductPriceChangeException;
import io.citybuddy.commerce.catalog.ProductPublicationService;
import io.citybuddy.commerce.catalog.ProductRepository.PriceChange;
import io.citybuddy.commerce.merchant.MerchantModels.Context;
import io.citybuddy.commerce.merchant.MerchantModels.DraftItem;
import io.citybuddy.commerce.merchant.MerchantModels.DraftView;
import io.citybuddy.commerce.merchant.MerchantModels.PrepareCommand;
import io.citybuddy.commerce.merchant.MerchantModels.PriceInput;
import io.citybuddy.commerce.merchant.MerchantModels.ProductView;
import io.citybuddy.commerce.merchant.MerchantModels.StoredDraft;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class MerchantService {
  private final MerchantRepository repository;
  private final ProductPublicationService prices;
  private final ObjectMapper mapper;
  private final Clock clock;

  public MerchantService(
      MerchantRepository repository,
      ProductPublicationService prices,
      ObjectMapper mapper,
      Clock clock) {
    this.repository = repository;
    this.prices = prices;
    this.mapper = mapper;
    this.clock = clock;
  }

  public List<ProductView> products() {
    return repository.products();
  }

  public ProductView product(String id) {
    return repository.product(id).orElseThrow(() -> notFound("Product not found"));
  }

  public Map<String, Object> summary(Instant start, Instant end) {
    if (!start.isBefore(end) || end.isAfter(start.plusSeconds(366L * 86400))) {
      throw invalid("Query period must be positive and at most 366 days");
    }
    return Map.of(
        "start",
        start,
        "end",
        end,
        "basis",
        "paid_gross_before_refunds",
        "currencies",
        repository.summary(start, end));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public DraftView prepare(Context context, String key, PrepareCommand command) {
    requireText(key, "Idempotency-Key", 128);
    PrepareCommand normalized = normalize(command);
    String hash = intentHash(normalized);
    var existing = repository.findByRequest(context, key);
    if (existing.isPresent()) {
      return replay(existing.get(), hash);
    }
    List<DraftItem> items = new ArrayList<>();
    var canonicalIds = new HashSet<String>();
    for (PriceInput input : normalized.items()) {
      var found = repository.product(input.productId());
      if (found.isEmpty()) {
        return replayOrReject(context, key, hash, notFound("Product not found"));
      }
      ProductView product = found.get();
      if (!canonicalIds.add(product.productId())) {
        throw invalid("Products must resolve to distinct catalog entries");
      }
      if (!product.priceEditable() || !product.currency().equals(normalized.currency())) {
        return replayOrReject(
            context,
            key,
            hash,
            new MerchantException(409, "PRODUCT_NOT_EDITABLE", "Product cannot be repriced"));
      }
      items.add(
          new DraftItem(
              product.productId(),
              product.name(),
              product.priceMinor(),
              input.newPriceMinor(),
              product.currency(),
              product.publicationVersion()));
    }
    StoredDraft stored =
        repository.insertOrReplay(
            UUID.randomUUID().toString(),
            context,
            key,
            hash,
            normalized.currency(),
            items,
            clock.instant());
    return replay(stored, hash);
  }

  public DraftView get(Context context, String id) {
    return owned(context, id, false).view();
  }

  @Transactional
  public DraftView cancel(Context context, String id) {
    StoredDraft draft = owned(context, id, true);
    if (!draft.view().state().equals("PREPARED")) {
      return draft.view();
    }
    return repository.resolve(
        id, "CANCELLED", mapper.valueToTree(Map.of("status", "CANCELLED")), clock.instant());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public DraftView apply(String operator, String id) {
    StoredDraft draft = repository.find(id, true).orElseThrow(() -> notFound("Draft not found"));
    if (!draft.operatorSubject().equals(operator)) {
      throw notFound("Draft not found");
    }
    if (!draft.view().state().equals("PREPARED")) {
      return draft.view();
    }
    try {
      var changes =
          prices.changePrices(
              draft.view().items().stream()
                  .map(
                      item ->
                          new PriceChange(
                              item.productId(), item.expectedVersion(), item.newPriceMinor()))
                  .toList(),
              draft.view().currency());
      return repository.resolve(
          id,
          "APPLIED",
          mapper.valueToTree(Map.of("status", "APPLIED", "changes", changes)),
          clock.instant());
    } catch (ProductPriceChangeException rejected) {
      return repository.resolve(
          id,
          "REJECTED",
          mapper.valueToTree(
              Map.of(
                  "status",
                  "REJECTED",
                  "reason",
                  rejected.reason(),
                  "productId",
                  rejected.productId())),
          clock.instant());
    }
  }

  private StoredDraft owned(Context context, String id, boolean lock) {
    StoredDraft draft = repository.find(id, lock).orElseThrow(() -> notFound("Draft not found"));
    if (!draft.operatorSubject().equals(context.operatorSubject())
        || !draft.sessionId().equals(context.sessionId())) {
      throw notFound("Draft not found");
    }
    return draft;
  }

  private static DraftView replay(StoredDraft stored, String hash) {
    if (!stored.intentHash().equals(hash)) {
      throw new MerchantException(
          409, "IDEMPOTENCY_CONFLICT", "Key already names another proposal");
    }
    return stored.view();
  }

  private DraftView replayOrReject(
      Context context, String key, String hash, MerchantException rejected) {
    // A competing prepare can commit between our first lookup and the catalog read.
    // READ_COMMITTED lets this rejection path return that immutable result instead.
    return repository
        .findByRequest(context, key)
        .map(draft -> replay(draft, hash))
        .orElseThrow(() -> rejected);
  }

  static PrepareCommand normalize(PrepareCommand command) {
    if (command == null
        || command.currency() == null
        || !command.currency().matches("[A-Z]{3}")
        || command.items() == null
        || command.items().isEmpty()
        || command.items().size() > 3) {
      throw invalid("A proposal requires a currency and one to three products");
    }
    var ids = new HashSet<String>();
    for (PriceInput item : command.items()) {
      if (item == null) {
        throw invalid("Price item is missing");
      }
      requireText(item.productId(), "productId", 64);
      if (item.newPriceMinor() <= 0 || !ids.add(item.productId())) {
        throw invalid("Prices must be positive and products unique");
      }
    }
    return new PrepareCommand(
        command.currency(),
        command.items().stream().sorted(Comparator.comparing(PriceInput::productId)).toList());
  }

  private String intentHash(PrepareCommand command) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(command)));
    } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Cannot encode merchant intent", exception);
    }
  }

  static void requireText(String value, String field, int maximum) {
    if (value == null || value.isBlank() || value.length() > maximum) {
      throw invalid(field + " is invalid");
    }
  }

  static MerchantException invalid(String message) {
    return new MerchantException(400, "VALIDATION", message);
  }

  private static MerchantException notFound(String message) {
    return new MerchantException(404, "NOT_FOUND", message);
  }
}
