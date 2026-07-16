package io.citybuddy.commerce.seckill;

import java.time.Clock;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;

public final class SeckillTimeoutWorker {
  private final SeckillTimeoutDispatchService dispatch;
  private final RocketMqSeckillTimeouts messaging;
  private final SeckillCancellationService cancellations;
  private final SeckillTimeoutProperties properties;
  private final Instant activationCutoff;
  private boolean activationComplete;

  public SeckillTimeoutWorker(
      SeckillTimeoutDispatchService dispatch,
      RocketMqSeckillTimeouts messaging,
      SeckillCancellationService cancellations,
      SeckillTimeoutProperties properties,
      Clock clock) {
    this.dispatch = dispatch;
    this.messaging = messaging;
    this.cancellations = cancellations;
    this.properties = properties;
    activationCutoff = clock.instant();
  }

  @Scheduled(
      initialDelayString = "${citybuddy.seckill.timeout.dispatch-worker-initial-delay-ms:1000}",
      fixedDelayString = "${citybuddy.seckill.timeout.dispatch-worker-delay-ms:1000}")
  public SeckillTimeoutDispatchService.DispatchBatch dispatchOnce() {
    if (!activationComplete) {
      SeckillTimeoutDispatchService.DispatchBatch batch =
          dispatch.dispatchPreexistingOnce(activationCutoff);
      activationComplete = batch.selected() < properties.dispatchBatchSize();
      return batch;
    }
    return dispatch.dispatchCurrentOnce();
  }

  @Scheduled(
      initialDelayString = "${citybuddy.seckill.timeout.consumer-worker-initial-delay-ms:5000}",
      fixedDelayString = "${citybuddy.seckill.timeout.consumer-worker-delay-ms:1000}")
  public int consumeOnce() throws Exception {
    return messaging.consumeOnce(cancellations);
  }
}
