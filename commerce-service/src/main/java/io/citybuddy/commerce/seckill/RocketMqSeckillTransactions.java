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
  static final String RESERVED_SANDBOX_PROPERTY = "citybuddy-eval-sandbox-id";

  private final ClientServiceProvider provider;
  private final ObjectMapper objectMapper;
  private final SeckillOrderProperties properties;
  private final SeckillReservationService reservationService;
  private final Producer producer;
  private final SimpleConsumer consumer;

  public RocketMqSeckillTransactions(
      ObjectMapper objectMapper,
      SeckillOrderProperties properties,
      SeckillReservationService reservationService)
      throws ClientException {
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.reservationService = reservationService;
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
      ReservationAdmissionStore.AdmissionHandoff handoff,
      SeckillReservationService reservationService)
      throws ClientException {
    SeckillTransactionMessage payload = SeckillTransactionMessage.from(handoff);
    Message message = message(payload);
    Transaction transaction = producer.beginTransaction();
    producer.send(message, transaction);
    ReservationResult result = reservationService.persistAdmitted(handoff);
    boolean committed = false;
    try {
      transaction.commit();
      committed = true;
    } catch (ClientException exception) {
      // The checker reads the durable MySQL reservation after an uncertain second phase.
    }
    if (committed) {
      reservationService.completeAdmissionHandoff(handoff);
    }
    return result;
  }

  public int consumeOnce(SeckillOrderService orderService) throws ClientException {
    List<MessageView> messages =
        consumer.receive(properties.receiveBatchSize(), properties.receiveInvisibleDuration());
    return consumeBatch(objectMapper, orderService, consumer, messages);
  }

  static int consumeBatch(
      ObjectMapper objectMapper,
      SeckillOrderService orderService,
      SimpleConsumer consumer,
      List<MessageView> messages)
      throws ClientException {
    int consumed = 0;
    Exception firstFailure = null;
    for (MessageView message : messages) {
      try {
        rejectEvaluationContext(message);
        orderService.create(payload(objectMapper, message));
        consumer.ack(message);
        consumed++;
      } catch (RuntimeException | ClientException exception) {
        if (firstFailure == null) {
          firstFailure = exception;
        } else if (firstFailure != exception) {
          firstFailure.addSuppressed(exception);
        }
      }
    }
    if (firstFailure instanceof ClientException clientException) {
      throw clientException;
    }
    if (firstFailure != null) {
      throw (RuntimeException) firstFailure;
    }
    return consumed;
  }

  TransactionResolution check(MessageView message) {
    try {
      return reservationService.transactionResolution(singleKey(message));
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

  private static SeckillTransactionMessage payload(ObjectMapper objectMapper, MessageView message) {
    try {
      ByteBuffer body = message.getBody();
      byte[] bytes = new byte[body.remaining()];
      body.get(bytes);
      return objectMapper.readValue(bytes, SeckillTransactionMessage.class);
    } catch (Exception exception) {
      throw new IllegalArgumentException("Seckill transaction message is malformed", exception);
    }
  }

  private static void rejectEvaluationContext(MessageView message) {
    if (message.getProperties().containsKey(RESERVED_SANDBOX_PROPERTY)) {
      throw new IllegalArgumentException(
          "Production seckill transaction cannot carry evaluation context");
    }
  }

  @Override
  public void close() throws Exception {
    consumer.close();
    producer.close();
  }
}
