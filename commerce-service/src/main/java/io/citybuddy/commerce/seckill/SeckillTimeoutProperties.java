package io.citybuddy.commerce.seckill;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("citybuddy.seckill.timeout")
public record SeckillTimeoutProperties(
    String rocketmqEndpoints,
    String rocketmqTopic,
    String rocketmqConsumerGroup,
    Duration receiveAwait,
    Duration receiveInvisibleDuration,
    Integer receiveBatchSize,
    Integer dispatchBatchSize,
    Integer maximumDispatchAttempts,
    Integer maximumDeliveryAttempts) {

  public SeckillTimeoutProperties {
    receiveAwait = receiveAwait == null ? Duration.ofSeconds(2) : receiveAwait;
    receiveInvisibleDuration =
        receiveInvisibleDuration == null ? Duration.ofSeconds(30) : receiveInvisibleDuration;
    receiveBatchSize = receiveBatchSize == null ? 16 : receiveBatchSize;
    dispatchBatchSize = dispatchBatchSize == null ? 32 : dispatchBatchSize;
    maximumDispatchAttempts = maximumDispatchAttempts == null ? 5 : maximumDispatchAttempts;
    maximumDeliveryAttempts = maximumDeliveryAttempts == null ? 3 : maximumDeliveryAttempts;
    requireText(rocketmqEndpoints, "RocketMQ endpoints");
    requireText(rocketmqTopic, "RocketMQ timeout topic");
    requireText(rocketmqConsumerGroup, "RocketMQ timeout consumer group");
    requirePositive(receiveAwait, "Receive await duration");
    if (receiveInvisibleDuration.compareTo(Duration.ofSeconds(10)) < 0
        || receiveInvisibleDuration.compareTo(Duration.ofHours(12)) > 0) {
      throw new IllegalArgumentException(
          "Receive invisible duration must be between 10 seconds and 12 hours");
    }
    requireBounded(receiveBatchSize, 32, "Receive batch size");
    requireBounded(dispatchBatchSize, 1_000, "Dispatch batch size");
    requireBounded(maximumDispatchAttempts, 100, "Maximum dispatch attempts");
    requireBounded(maximumDeliveryAttempts, 100, "Maximum delivery attempts");
  }

  private static void requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " is required");
    }
  }

  private static void requirePositive(Duration value, String label) {
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(label + " must be positive");
    }
  }

  private static void requireBounded(int value, int maximum, String label) {
    if (value < 1 || value > maximum) {
      throw new IllegalArgumentException(label + " must be between 1 and " + maximum);
    }
  }
}
