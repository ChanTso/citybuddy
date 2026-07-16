package io.citybuddy.commerce.seckill;

import java.time.Instant;
import java.util.List;
import org.apache.rocketmq.client.apis.ClientException;

public final class SeckillTimeoutDispatchService {
  private final SeckillOrderRepository orders;
  private final SeckillTimeoutPublisher publisher;
  private final SeckillTimeoutProperties properties;

  public SeckillTimeoutDispatchService(
      SeckillOrderRepository orders,
      SeckillTimeoutPublisher publisher,
      SeckillTimeoutProperties properties) {
    this.orders = orders;
    this.publisher = publisher;
    this.properties = properties;
  }

  public DispatchBatch dispatchPreexistingOnce(Instant activationCutoff) {
    if (activationCutoff == null) {
      throw new IllegalArgumentException("Activation cutoff is required");
    }
    return dispatchOnce(activationCutoff);
  }

  public DispatchBatch dispatchCurrentOnce() {
    return dispatchOnce(null);
  }

  private DispatchBatch dispatchOnce(Instant activationCutoff) {
    List<SeckillOrderRepository.OrderRecord> batch =
        orders.findPendingTimeoutDispatches(
            activationCutoff, properties.maximumDispatchAttempts(), properties.dispatchBatchSize());
    int sent = 0;
    int failed = 0;
    for (SeckillOrderRepository.OrderRecord order : batch) {
      final String brokerMessageId;
      try {
        brokerMessageId = publisher.send(SeckillTimeoutMessage.from(order));
      } catch (ClientException exception) {
        orders.recordTimeoutDispatchFailure(
            order, properties.maximumDispatchAttempts(), failure(exception));
        failed++;
        continue;
      }
      if (brokerMessageId == null || brokerMessageId.isBlank()) {
        throw new IllegalStateException("RocketMQ send returned no durable message identity");
      }
      orders.markTimeoutDispatched(order, brokerMessageId);
      sent++;
    }
    return new DispatchBatch(batch.size(), sent, failed);
  }

  private static String failure(ClientException exception) {
    String message = exception.getMessage();
    return exception.getClass().getSimpleName()
        + (message == null || message.isBlank() ? "" : ": " + message);
  }

  public record DispatchBatch(int selected, int sent, int failed) {}
}
