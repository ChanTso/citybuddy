package io.citybuddy.commerce.seckill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.Transaction;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;

public final class RocketMqSeckillTransactions implements AutoCloseable {
  static final String TAG = "seckill-order";

  private final ClientServiceProvider provider;
  private final ObjectMapper objectMapper;
  private final SeckillOrderProperties properties;
  private final ReservationAdmissionStore admissionStore;
  private final Producer producer;
  private final SimpleConsumer consumer;

  public RocketMqSeckillTransactions(
      ObjectMapper objectMapper,
      SeckillOrderProperties properties,
      ReservationAdmissionStore admissionStore)
      throws ClientException {
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.admissionStore = admissionStore;
    provider = ClientServiceProvider.loadService();
    ClientConfiguration configuration =
        ClientConfiguration.newBuilder()
            .setEndpoints(properties.rocketmqEndpoints())
            .setRequestTimeout(Duration.ofSeconds(10))
            .enableSsl(false)
            .build();
    Producer builtProducer =
        provider
            .newProducerBuilder()
            .setClientConfiguration(configuration)
            .setTopics(properties.rocketmqTopic())
            .setTransactionChecker(this::check)
            .build();
    try {
      consumer =
          provider
              .newSimpleConsumerBuilder()
              .setClientConfiguration(configuration)
              .setConsumerGroup(properties.rocketmqConsumerGroup())
              .setAwaitDuration(properties.receiveAwait())
              .setSubscriptionExpressions(
                  Collections.singletonMap(
                      properties.rocketmqTopic(),
                      new FilterExpression(TAG, FilterExpressionType.TAG)))
              .build();
      producer = builtProducer;
    } catch (ClientException exception) {
      try {
        builtProducer.close();
      } catch (java.io.IOException closeFailure) {
        exception.addSuppressed(closeFailure);
      }
      throw exception;
    }
  }

  public ReservationResult submit(
      SeckillReservation reservation, SeckillReservationService reservationService)
      throws ClientException {
    SeckillTransactionMessage payload = SeckillTransactionMessage.from(reservation);
    Message message = message(payload);
    Transaction transaction = producer.beginTransaction();
    producer.send(message, transaction);
    ReservationResult result = reservationService.admit(reservation.reservationId());
    try {
      if (result.state() == ReservationState.ADMITTED) {
        transaction.commit();
      } else if (result.state() == ReservationState.REJECTED) {
        transaction.rollback();
      } else {
        throw new IllegalStateException("Lua decision did not produce a terminal marker");
      }
    } catch (ClientException exception) {
      // The durable marker remains the sole checker authority after an uncertain second phase.
    }
    return result;
  }

  public int consumeOnce(SeckillOrderService orderService) throws ClientException {
    List<MessageView> messages =
        consumer.receive(properties.receiveBatchSize(), properties.receiveInvisibleDuration());
    int consumed = 0;
    for (MessageView message : messages) {
      orderService.create(payload(message));
      consumer.ack(message);
      consumed++;
    }
    return consumed;
  }

  TransactionResolution check(MessageView message) {
    try {
      return admissionStore.transactionResolution(singleKey(message));
    } catch (RuntimeException exception) {
      return TransactionResolution.UNKNOWN;
    }
  }

  private static String singleKey(MessageView message) {
    if (message.getKeys().size() != 1) {
      throw new IllegalArgumentException("Transaction message must have one reservation key");
    }
    String reservationId = message.getKeys().iterator().next();
    if (reservationId == null
        || reservationId.isBlank()
        || reservationId.length() != 36
        || !reservationId.equals(reservationId.strip())) {
      throw new IllegalArgumentException("Transaction reservation key is invalid");
    }
    return reservationId;
  }

  private Message message(SeckillTransactionMessage payload) {
    return provider
        .newMessageBuilder()
        .setTopic(properties.rocketmqTopic())
        .setTag(TAG)
        .setKeys(payload.eventId())
        .setBody(json(payload))
        .build();
  }

  private byte[] json(SeckillTransactionMessage payload) {
    try {
      return objectMapper.writeValueAsBytes(payload);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Seckill transaction message serialization failed", exception);
    }
  }

  private SeckillTransactionMessage payload(MessageView message) {
    try {
      ByteBuffer body = message.getBody();
      byte[] bytes = new byte[body.remaining()];
      body.get(bytes);
      return objectMapper.readValue(bytes, SeckillTransactionMessage.class);
    } catch (Exception exception) {
      throw new IllegalArgumentException("Seckill transaction message is malformed", exception);
    }
  }

  @Override
  public void close() throws Exception {
    consumer.close();
    producer.close();
  }
}
