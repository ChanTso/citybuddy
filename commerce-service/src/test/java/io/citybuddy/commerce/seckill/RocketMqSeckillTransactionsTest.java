package io.citybuddy.commerce.seckill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;

class RocketMqSeckillTransactionsTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final SeckillOrderService orderService = mock(SeckillOrderService.class);
  private final SimpleConsumer consumer = mock(SimpleConsumer.class);

  @Test
  void malformedMessageDoesNotStarveTheLaterValidMessage() throws Exception {
    MessageView malformed = message(new byte[] {'{'});
    SeckillTransactionMessage validPayload = payload("00000000-0000-0000-0000-000000000002");
    MessageView valid = message(objectMapper.writeValueAsBytes(validPayload));

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RocketMqSeckillTransactions.consumeBatch(
                    objectMapper, orderService, consumer, List.of(malformed, valid)));

    assertThat(failure).hasMessage("Seckill transaction message is malformed");
    verify(orderService).create(validPayload);
    verify(consumer, never()).ack(malformed);
    verify(consumer).ack(valid);
  }

  @Test
  void ackAndBusinessFailuresRemainVisibleAfterLaterMessagesConverge() throws Exception {
    SeckillTransactionMessage businessPayload = payload("00000000-0000-0000-0000-000000000011");
    SeckillTransactionMessage ackPayload = payload("00000000-0000-0000-0000-000000000012");
    SeckillTransactionMessage validPayload = payload("00000000-0000-0000-0000-000000000013");
    MessageView businessFailureMessage = message(objectMapper.writeValueAsBytes(businessPayload));
    MessageView ackFailureMessage = message(objectMapper.writeValueAsBytes(ackPayload));
    MessageView validMessage = message(objectMapper.writeValueAsBytes(validPayload));
    IllegalStateException businessFailure =
        new IllegalStateException("controlled database failure");
    ClientException ackFailure = new ClientException("controlled acknowledgement failure");
    doThrow(businessFailure).when(orderService).create(businessPayload);
    doThrow(ackFailure).when(consumer).ack(ackFailureMessage);

    ClientException thrown =
        assertThrows(
            ClientException.class,
            () ->
                RocketMqSeckillTransactions.consumeBatch(
                    objectMapper,
                    orderService,
                    consumer,
                    List.of(ackFailureMessage, businessFailureMessage, validMessage)));

    assertThat(thrown).isSameAs(ackFailure);
    assertThat(thrown.getSuppressed()).containsExactly(businessFailure);
    verify(orderService).create(businessPayload);
    verify(orderService).create(ackPayload);
    verify(orderService).create(validPayload);
    verify(consumer, never()).ack(businessFailureMessage);
    verify(consumer).ack(ackFailureMessage);
    verify(consumer).ack(validMessage);
  }

  private MessageView message(byte[] body) {
    MessageView message = mock(MessageView.class);
    when(message.getProperties()).thenReturn(Map.of());
    when(message.getBody()).thenReturn(ByteBuffer.wrap(body));
    return message;
  }

  private static SeckillTransactionMessage payload(String reservationId) {
    return new SeckillTransactionMessage(
        reservationId, reservationId, "activity-1", "user-1", 1, 1);
  }
}
