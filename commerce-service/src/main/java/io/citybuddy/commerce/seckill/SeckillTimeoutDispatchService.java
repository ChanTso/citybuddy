package io.citybuddy.commerce.seckill;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.rocketmq.client.apis.ClientException;
import org.springframework.transaction.support.TransactionTemplate;

public final class SeckillTimeoutDispatchService {
  private final SeckillOrderRepository orders;
  private final SeckillTimeoutPublisher publisher;
  private final SeckillTimeoutProperties properties;
  private final TransactionTemplate transactions;

  public SeckillTimeoutDispatchService(
      SeckillOrderRepository orders,
      SeckillTimeoutPublisher publisher,
      SeckillTimeoutProperties properties,
      TransactionTemplate transactions) {
    this.orders = orders;
    this.publisher = publisher;
    this.properties = properties;
    this.transactions = transactions;
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
        orders.findRecoverableTimeoutDispatches(activationCutoff, properties.dispatchBatchSize());
    int sent = 0;
    int failed = 0;
    List<DispatchReceipt> receipts = new ArrayList<>(batch.size());
    for (SeckillOrderRepository.OrderRecord order : batch) {
      final String brokerMessageId;
      try {
        brokerMessageId = publisher.send(SeckillTimeoutMessage.from(order));
      } catch (ClientException exception) {
        receipts.add(new DispatchReceipt(order, null, failure(exception)));
        failed++;
        continue;
      }
      if (brokerMessageId == null || brokerMessageId.isBlank()) {
        throw new IllegalStateException("RocketMQ send returned no durable message identity");
      }
      receipts.add(new DispatchReceipt(order, brokerMessageId, null));
      sent++;
    }
    // Network sends hold no database locks. If receipt persistence fails, the batch can be
    // sent again; the timeout consumer uses the order's durable cancellation state for replay.
    if (!receipts.isEmpty()) {
      transactions.executeWithoutResult(
          status -> {
            for (DispatchReceipt receipt : receipts) {
              if (receipt.failure() == null) {
                orders.markTimeoutDispatched(receipt.order(), receipt.brokerMessageId());
              } else {
                orders.recordTimeoutDispatchFailure(receipt.order(), receipt.failure());
              }
            }
          });
    }
    return new DispatchBatch(batch.size(), sent, failed);
  }

  private static String failure(ClientException exception) {
    String message = exception.getMessage();
    return exception.getClass().getSimpleName()
        + (message == null || message.isBlank() ? "" : ": " + message);
  }

  private record DispatchReceipt(
      SeckillOrderRepository.OrderRecord order, String brokerMessageId, String failure) {}

  public record DispatchBatch(int selected, int sent, int failed) {}
}
