package io.citybuddy.commerce.seckill;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("citybuddy.seckill.order")
public record SeckillOrderProperties(
    String requiredPermission,
    String rocketmqEndpoints,
    String rocketmqTopic,
    String rocketmqConsumerGroup,
    Duration unpaidTimeout,
    Duration receiveAwait,
    Duration receiveInvisibleDuration,
    Integer receiveBatchSize) {

  public SeckillOrderProperties {
    requiredPermission =
        requiredPermission == null || requiredPermission.isBlank()
            ? "seckill:reserve"
            : requiredPermission;
    unpaidTimeout = unpaidTimeout == null ? Duration.ofMinutes(15) : unpaidTimeout;
    receiveAwait = receiveAwait == null ? Duration.ofSeconds(2) : receiveAwait;
    receiveInvisibleDuration =
        receiveInvisibleDuration == null ? Duration.ofSeconds(30) : receiveInvisibleDuration;
    receiveBatchSize = receiveBatchSize == null ? 16 : receiveBatchSize;
    requireText(rocketmqEndpoints, "RocketMQ endpoints");
    requireText(rocketmqTopic, "RocketMQ topic");
    requireText(rocketmqConsumerGroup, "RocketMQ consumer group");
    requirePositive(unpaidTimeout, "Unpaid timeout");
    requirePositive(receiveAwait, "Receive await duration");
    if (receiveInvisibleDuration.compareTo(Duration.ofSeconds(10)) < 0
        || receiveInvisibleDuration.compareTo(Duration.ofHours(12)) > 0) {
      throw new IllegalArgumentException(
          "Receive invisible duration must be between 10 seconds and 12 hours");
    }
    if (receiveBatchSize < 1 || receiveBatchSize > 32) {
      throw new IllegalArgumentException("Receive batch size must be between 1 and 32");
    }
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
}
