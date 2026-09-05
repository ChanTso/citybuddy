package io.citybuddy.commerce.seckill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.Test;

class RocketMqSeckillTimeoutsTest {
  private static final Duration RECEIVE_INVISIBLE = Duration.ofSeconds(10);

  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private final SeckillCancellationService cancellations = mock(SeckillCancellationService.class);
  private final SimpleConsumer consumer = mock(SimpleConsumer.class);
  private final RocketMqSeckillTimeouts messaging =
      new RocketMqSeckillTimeouts(
          objectMapper,
          new SeckillTimeoutProperties(
              "proxy:8081",
              "timeouts",
              "timeout-consumer",
              Duration.ofSeconds(1),
              RECEIVE_INVISIBLE,
              16,
              32),
          mock(ClientServiceProvider.class),
          mock(Producer.class),
          consumer);

  @Test
  void malformedAndBusinessFailuresLeaveLaterMessagesProcessable() throws Exception {
    MessageView malformed = mock(MessageView.class);
    when(malformed.getProperties()).thenReturn(Map.of());
    when(malformed.getBody()).thenReturn(ByteBuffer.wrap(new byte[] {'{'}));
    SeckillTimeoutMessage failedPayload = payload();
    SeckillTimeoutMessage laterPayload = payload();
    MessageView failed = message(failedPayload, 1);
    MessageView later = message(laterPayload, 1);
    IllegalStateException businessFailure =
        new IllegalStateException("controlled database failure");
    when(cancellations.cancel(failedPayload)).thenThrow(businessFailure);
    when(cancellations.cancel(laterPayload)).thenReturn(terminal());
    when(consumer.receive(16, RECEIVE_INVISIBLE)).thenReturn(List.of(malformed, failed, later));

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> messaging.consumeOnce(cancellations));

    assertThat(thrown).hasMessage("Seckill timeout message is malformed");
    assertThat(thrown.getSuppressed()).containsExactly(businessFailure);
    verify(consumer, never()).ack(malformed);
    verify(consumer, never()).ack(failed);
    verify(consumer).ack(later);
  }

  @Test
  void ackFailureDoesNotBlockLaterMessageOrHigherAttemptReplay() throws Exception {
    SeckillTimeoutMessage firstPayload = payload();
    SeckillTimeoutMessage laterPayload = payload();
    MessageView first = message(firstPayload, 4);
    MessageView later = message(laterPayload, 1);
    when(consumer.receive(16, RECEIVE_INVISIBLE)).thenReturn(List.of(first, later), List.of(first));
    when(cancellations.cancel(firstPayload)).thenReturn(terminal());
    when(cancellations.cancel(laterPayload)).thenReturn(terminal());
    ClientException ackFailure = new ClientException("controlled acknowledgement failure");
    doThrow(ackFailure).doNothing().when(consumer).ack(first);

    ClientException thrown =
        assertThrows(ClientException.class, () -> messaging.consumeOnce(cancellations));

    assertThat(thrown).isSameAs(ackFailure);
    verify(cancellations).cancel(laterPayload);
    verify(consumer).ack(later);
    assertThat(messaging.consumeOnce(cancellations)).isEqualTo(1);
    verify(cancellations, times(2)).cancel(firstPayload);
    verify(consumer, times(2)).ack(first);
  }

  @Test
  void controlFailuresRemainVisibleWhileLaterMessagesContinue() throws Exception {
    SeckillTimeoutMessage earlyPayload = payload();
    SeckillTimeoutMessage ackPayload = payload();
    SeckillTimeoutMessage laterPayload = payload();
    MessageView early = message(earlyPayload, 1);
    MessageView ack = message(ackPayload, 1);
    MessageView later = message(laterPayload, 1);
    Duration retryAfter = Duration.ofSeconds(17);
    when(consumer.receive(16, RECEIVE_INVISIBLE)).thenReturn(List.of(early, ack, later));
    when(cancellations.cancel(earlyPayload))
        .thenReturn(
            new SeckillCancellationService.CancellationResult(
                SeckillCancellationService.Outcome.EARLY, retryAfter));
    when(cancellations.cancel(ackPayload)).thenReturn(terminal());
    when(cancellations.cancel(laterPayload)).thenReturn(terminal());
    ClientException visibilityFailure =
        new ClientException("controlled change-invisibility failure");
    ClientException ackFailure = new ClientException("controlled acknowledgement failure");
    doThrow(visibilityFailure).when(consumer).changeInvisibleDuration(early, retryAfter);
    doThrow(ackFailure).when(consumer).ack(ack);

    ClientException thrown =
        assertThrows(ClientException.class, () -> messaging.consumeOnce(cancellations));

    assertThat(thrown).isSameAs(visibilityFailure);
    assertThat(thrown.getSuppressed()).containsExactly(ackFailure);
    verify(consumer).changeInvisibleDuration(early, retryAfter);
    verify(consumer, never()).ack(early);
    verify(consumer).ack(ack);
    verify(cancellations).cancel(laterPayload);
    verify(consumer).ack(later);
  }

  private MessageView message(SeckillTimeoutMessage payload, int deliveryAttempt) throws Exception {
    byte[] body = objectMapper.writeValueAsBytes(payload);
    MessageView message = mock(MessageView.class);
    when(message.getProperties()).thenReturn(Map.of());
    when(message.getDeliveryAttempt()).thenReturn(deliveryAttempt);
    when(message.getBody()).thenAnswer(ignored -> ByteBuffer.wrap(body));
    return message;
  }

  private static SeckillTimeoutMessage payload() {
    return new SeckillTimeoutMessage(
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        "UNPAID",
        1,
        Instant.parse("2026-09-03T00:00:00Z"),
        UUID.randomUUID().toString());
  }

  private static SeckillCancellationService.CancellationResult terminal() {
    return new SeckillCancellationService.CancellationResult(
        SeckillCancellationService.Outcome.FINAL_PRESERVED, Duration.ZERO);
  }
}
