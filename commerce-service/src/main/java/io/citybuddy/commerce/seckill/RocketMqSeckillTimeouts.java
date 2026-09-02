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

public final class RocketMqSeckillTimeouts implements AutoCloseable, SeckillTimeoutPublisher {
  static final String TAG = "seckill-unpaid-timeout";

  private final ClientServiceProvider provider;
  private final ObjectMapper objectMapper;
  private final SeckillTimeoutProperties properties;
  private final Producer producer;
  private final SimpleConsumer consumer;

  public RocketMqSeckillTimeouts(ObjectMapper objectMapper, SeckillTimeoutProperties properties)
      throws ClientException {
    this.objectMapper = objectMapper;
    this.properties = properties;
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

  RocketMqSeckillTimeouts(
      ObjectMapper objectMapper,
      SeckillTimeoutProperties properties,
      ClientServiceProvider provider,
      Producer producer,
      SimpleConsumer consumer) {
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.provider = provider;
    this.producer = producer;
    this.consumer = consumer;
  }

  @Override
  public String send(SeckillTimeoutMessage payload) throws ClientException {
    return producer.send(message(payload)).getMessageId().toString();
  }

  public int consumeOnce(SeckillCancellationService cancellations) throws ClientException {
    List<MessageView> messages =
        consumer.receive(properties.receiveBatchSize(), properties.receiveInvisibleDuration());
    int consumed = 0;
    Exception firstFailure = null;
    for (MessageView message : messages) {
      try {
        rejectEvaluationContext(message);
        SeckillCancellationService.CancellationResult result =
            cancellations.cancel(payload(message));
        if (result.outcome() == SeckillCancellationService.Outcome.EARLY) {
          consumer.changeInvisibleDuration(message, result.retryAfter());
          continue;
        }
        consumer.ack(message);
        consumed++;
      } catch (ClientException | RuntimeException exception) {
        if (interrupted(exception)) {
          if (firstFailure != null) {
            exception.addSuppressed(firstFailure);
          }
          Thread.currentThread().interrupt();
          if (exception instanceof ClientException clientException) {
            throw clientException;
          }
          throw (RuntimeException) exception;
        }
        firstFailure = retain(firstFailure, exception);
      }
    }
    if (firstFailure instanceof ClientException clientException) {
      throw clientException;
    }
    if (firstFailure instanceof RuntimeException runtimeException) {
      throw runtimeException;
    }
    return consumed;
  }

  private static Exception retain(Exception firstFailure, Exception failure) {
    if (firstFailure == null) {
      return failure;
    }
    firstFailure.addSuppressed(failure);
    return firstFailure;
  }

  private static boolean interrupted(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof InterruptedException) {
        return true;
      }
      if (cause == cause.getCause()) {
        break;
      }
    }
    return Thread.currentThread().isInterrupted();
  }

  private Message message(SeckillTimeoutMessage payload) {
    validate(payload);
    return provider
        .newMessageBuilder()
        .setTopic(properties.rocketmqTopic())
        .setTag(TAG)
        .setKeys(payload.eventId())
        .setDeliveryTimestamp(payload.dueAt().toEpochMilli())
        .setBody(json(payload))
        .build();
  }

  private byte[] json(SeckillTimeoutMessage payload) {
    try {
      return objectMapper.writeValueAsBytes(payload);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Seckill timeout message serialization failed", exception);
    }
  }

  private SeckillTimeoutMessage payload(MessageView message) {
    try {
      ByteBuffer body = message.getBody();
      byte[] bytes = new byte[body.remaining()];
      body.get(bytes);
      SeckillTimeoutMessage payload = objectMapper.readValue(bytes, SeckillTimeoutMessage.class);
      validate(payload);
      return payload;
    } catch (Exception exception) {
      throw new IllegalArgumentException("Seckill timeout message is malformed", exception);
    }
  }

  private static void rejectEvaluationContext(MessageView message) {
    if (message
        .getProperties()
        .containsKey(RocketMqSeckillTransactions.RESERVED_SANDBOX_PROPERTY)) {
      throw new IllegalArgumentException(
          "Production seckill timeout cannot carry evaluation context");
    }
  }

  private static void validate(SeckillTimeoutMessage message) {
    if (message == null
        || !hasText(message.eventId(), 36)
        || !hasText(message.orderId(), 36)
        || !hasText(message.reservationId(), 36)
        || !"UNPAID".equals(message.expectedState())
        || message.expectedVersion() != 1
        || message.dueAt() == null
        || !hasText(message.correlationId(), 36)) {
      throw new IllegalArgumentException("Seckill timeout message is invalid");
    }
  }

  private static boolean hasText(String value, int maximumLength) {
    return value != null
        && !value.isBlank()
        && value.length() <= maximumLength
        && value.equals(value.strip());
  }

  @Override
  public void close() throws Exception {
    consumer.close();
    producer.close();
  }
}
