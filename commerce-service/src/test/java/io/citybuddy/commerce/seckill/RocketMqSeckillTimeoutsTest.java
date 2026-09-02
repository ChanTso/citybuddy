package io.citybuddy.commerce.seckill;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.Test;

class RocketMqSeckillTimeoutsTest {
  @Test
  void leavesOnlyMalformedMessageUnackedAndContinuesReceivedBatch() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    SeckillTimeoutProperties properties =
        new SeckillTimeoutProperties(
            "proxy:8081", "timeouts", "timeout-consumer", null, null, null, null);
    SimpleConsumer consumer = mock(SimpleConsumer.class);
    MessageView malformed = message("{");
    SeckillTimeoutMessage validPayload =
        new SeckillTimeoutMessage(
            "event-id",
            "order-id",
            "reservation-id",
            "UNPAID",
            1,
            Instant.parse("2026-09-03T00:00:00Z"),
            "correlation-id");
    MessageView valid = message(objectMapper.writeValueAsString(validPayload));
    when(consumer.receive(properties.receiveBatchSize(), properties.receiveInvisibleDuration()))
        .thenReturn(List.of(malformed, valid));
    SeckillCancellationService cancellations = mock(SeckillCancellationService.class);
    when(cancellations.cancel(validPayload))
        .thenReturn(
            new SeckillCancellationService.CancellationResult(
                SeckillCancellationService.Outcome.STALE, Duration.ZERO));
    RocketMqSeckillTimeouts timeouts =
        new RocketMqSeckillTimeouts(
            objectMapper,
            properties,
            mock(ClientServiceProvider.class),
            mock(Producer.class),
            consumer);

    assertThatThrownBy(() -> timeouts.consumeOnce(cancellations))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Seckill timeout message is malformed");

    verify(cancellations).cancel(validPayload);
    verify(consumer).ack(valid);
    verify(consumer, never()).ack(malformed);
  }

  private static MessageView message(String body) {
    MessageView message = mock(MessageView.class);
    when(message.getProperties()).thenReturn(Map.of());
    when(message.getBody()).thenReturn(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)));
    return message;
  }
}
