package io.citybuddy.commerce.merchant;

import io.citybuddy.commerce.merchant.MerchantListingModels.InventoryAlert;
import io.citybuddy.commerce.merchant.MerchantListingModels.Listing;
import io.citybuddy.commerce.merchant.MerchantListingModels.Page;
import io.citybuddy.commerce.merchant.MerchantListingModels.Search;
import io.citybuddy.commerce.merchant.MerchantListingModels.Window;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class MerchantListingService {
  private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Shanghai");
  private final MerchantListingRepository repository;
  private final Clock clock;

  public MerchantListingService(MerchantListingRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page<Listing> search(Search search, Instant asOf) {
    Window window = window(asOf);
    return page(repository.search(search, window), search.limit(), search.offset(), window);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Listing get(String id, Instant asOf) {
    String requested = MerchantListingModels.text(id, 64, "listing id");
    if (requested == null) {
      throw new MerchantException(400, "VALIDATION", "Listing id is required");
    }
    return repository
        .find(requested, window(asOf))
        .orElseThrow(() -> new MerchantException(404, "NOT_FOUND", "Listing does not exist"));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page<InventoryAlert> alerts(int limit, int offset, Instant asOf) {
    MerchantListingModels.page(limit, offset);
    Window window = window(asOf);
    return page(repository.alerts(limit, offset, window), limit, offset, window);
  }

  private Window window(Instant asOf) {
    Instant now = clock.instant();
    if (asOf != null && asOf.isAfter(now)) {
      throw new MerchantException(400, "VALIDATION", "asOf must not be in the future");
    }
    var end = (asOf == null ? now : asOf).atZone(SHOP_ZONE).toLocalDate();
    return new Window(
        end.minusDays(30).atStartOfDay(SHOP_ZONE).toInstant(),
        end.atStartOfDay(SHOP_ZONE).toInstant(),
        SHOP_ZONE.getId());
  }

  private static <T> Page<T> page(List<T> rows, int limit, int offset, Window window) {
    boolean more = rows.size() > limit;
    return new Page<>(
        List.copyOf(rows.subList(0, Math.min(rows.size(), limit))),
        more ? offset + limit : null,
        window);
  }
}
