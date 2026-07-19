package io.citybuddy.commerce.catalog;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;

public final class RocketMqCatalogMessaging
    implements CatalogOutboxPublisher.CatalogEventSender, AutoCloseable {
  private static final String TAG = "product-publication";
  static final String RESERVED_SANDBOX_PROPERTY = "citybuddy-eval-sandbox-id";

  private final ClientServiceProvider provider;
  private final CatalogProperties properties;
  private final Producer producer;
  private final SimpleConsumer consumer;

  public RocketMqCatalogMessaging(CatalogProperties properties) throws Exception {
    this.properties = properties;
    provider = ClientServiceProvider.loadService();
    ClientConfiguration configuration =
        ClientConfiguration.newBuilder()
            .setEndpoints(properties.rocketmqEndpoints())
            .setRequestTimeout(Duration.ofSeconds(10))
            .enableSsl(false)
            .build();
    SimpleConsumer builtConsumer =
        provider
            .newSimpleConsumerBuilder()
            .setClientConfiguration(configuration)
            .setConsumerGroup(properties.rocketmqConsumerGroup())
            .setAwaitDuration(Duration.ofSeconds(2))
            .setSubscriptionExpressions(
                Collections.singletonMap(
                    properties.rocketmqTopic(),
                    new FilterExpression(TAG, FilterExpressionType.TAG)))
            .build();
    try {
      producer =
          provider
              .newProducerBuilder()
              .setClientConfiguration(configuration)
              .setTopics(properties.rocketmqTopic())
              .build();
      consumer = builtConsumer;
    } catch (Exception exception) {
      builtConsumer.close();
      throw exception;
    }
  }

  @Override
  public void send(ProductRepository.OutboxEvent event) throws Exception {
    Message message =
        provider
            .newMessageBuilder()
            .setTopic(properties.rocketmqTopic())
            .setTag(TAG)
            .setKeys(event.eventId())
            .setBody(event.payload().getBytes(StandardCharsets.UTF_8))
            .build();
    producer.send(message);
  }

  public int consumeOnce(CatalogEventHandler handler) throws Exception {
    int consumed = 0;
    for (MessageView message : consumer.receive(16, Duration.ofSeconds(15))) {
      rejectEvaluationContext(message);
      handler.handle(body(message));
      consumer.ack(message);
      consumed++;
    }
    return consumed;
  }

  @Override
  public void close() throws Exception {
    consumer.close();
    producer.close();
  }

  private static String body(MessageView message) {
    ByteBuffer buffer = message.getBody();
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static void rejectEvaluationContext(MessageView message) {
    if (message.getProperties().containsKey(RESERVED_SANDBOX_PROPERTY)) {
      throw new IllegalArgumentException(
          "Production catalog message cannot carry evaluation context");
    }
  }
}
