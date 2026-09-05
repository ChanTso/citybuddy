package io.citybuddy.commerce.seckill;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SeckillTimeoutWorkerTest {
  @Test
  void failedActivationBatchDoesNotExcludeOrdersCreatedAfterStartup() {
    SeckillTimeoutDispatchService dispatch = mock(SeckillTimeoutDispatchService.class);
    Instant cutoff = Instant.parse("2026-09-05T00:00:00Z");
    SeckillTimeoutProperties properties =
        new SeckillTimeoutProperties("proxy:8081", "timeouts", "consumer", null, null, null, 1);
    when(dispatch.dispatchPreexistingOnce(cutoff))
        .thenReturn(new SeckillTimeoutDispatchService.DispatchBatch(1, 0, 1));
    SeckillTimeoutWorker worker =
        new SeckillTimeoutWorker(
            dispatch,
            mock(RocketMqSeckillTimeouts.class),
            mock(SeckillCancellationService.class),
            properties,
            Clock.fixed(cutoff, ZoneOffset.UTC));

    worker.dispatchOnce();
    worker.dispatchOnce();

    verify(dispatch).dispatchPreexistingOnce(cutoff);
    verify(dispatch).dispatchCurrentOnce();
  }
}
