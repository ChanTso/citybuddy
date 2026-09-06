package io.citybuddy.commerce.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.citybuddy.commerce.merchant.MerchantListingModels.Search;
import io.citybuddy.commerce.merchant.MerchantListingModels.Window;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class MerchantListingServiceTest {
  private final MerchantListingRepository repository = mock(MerchantListingRepository.class);
  private final MerchantListingService service =
      new MerchantListingService(
          repository, Clock.fixed(Instant.parse("2026-09-06T16:00:00Z"), ZoneOffset.UTC));

  @Test
  void thirtyCompleteDaysChangeAtShanghaiMidnightAndLeaveCurrentPartialDayOut() {
    when(repository.search(any(), any())).thenReturn(List.of());
    Search search = new Search(null, null, null, null, null, null, null, 20, 0);
    var page = service.search(search, null);
    Window after =
        new Window(
            Instant.parse("2026-08-07T16:00:00Z"),
            Instant.parse("2026-09-06T16:00:00Z"),
            "Asia/Shanghai");
    assertThat(page.window()).isEqualTo(after);
    verify(repository).search(search, after);
    assertThat(service.search(search, Instant.parse("2026-09-06T15:59:59.999999Z")).window())
        .isEqualTo(
            new Window(
                Instant.parse("2026-08-06T16:00:00Z"),
                Instant.parse("2026-09-05T16:00:00Z"),
                "Asia/Shanghai"));
  }

  @Test
  void invalidBoundsAndMissingIdentifiersNeverReachTheDatabase() {
    assertThatThrownBy(() -> service.alerts(51, 0, null)).isInstanceOf(MerchantException.class);
    assertThatThrownBy(() -> service.alerts(10, -1, null)).isInstanceOf(MerchantException.class);
    assertThatThrownBy(() -> service.get(" ", null)).isInstanceOf(MerchantException.class);
    assertThatThrownBy(() -> service.alerts(10, 0, Instant.parse("2026-09-07T00:00:00Z")))
        .isInstanceOf(MerchantException.class);
    verifyNoInteractions(repository);
  }
}
