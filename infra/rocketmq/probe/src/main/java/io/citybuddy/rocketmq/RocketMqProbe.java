package io.citybuddy.rocketmq;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;

public final class RocketMqProbe {
  private static final String TAG = "cb013-probe";
  private static final Duration RECEIVE_AWAIT = Duration.ofSeconds(2);
  private static final Duration INVISIBLE_DURATION = Duration.ofSeconds(15);
  private static final Duration ROUND_TRIP_TIMEOUT = Duration.ofSeconds(30);

  private RocketMqProbe() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 3 && "route".equals(args[0])) {
      verifyRoute(args[1], args[2]);
      return;
    }
    if (args.length == 5 && "roundtrip".equals(args[0])) {
      roundTrip(args[1], args[2], args[3], args[4]);
      return;
    }
    throw new IllegalArgumentException(
        "Usage: route <proxy-endpoint> <topic> | roundtrip <proxy-endpoint> <topic> <consumer-group> <message-key>");
  }

  private static ClientConfiguration clientConfiguration(String endpoints) {
    return ClientConfiguration.newBuilder()
        .setEndpoints(endpoints)
        .setRequestTimeout(Duration.ofSeconds(10))
        .enableSsl(false)
        .build();
  }

  private static void verifyRoute(String endpoints, String topic) throws Exception {
    ClientServiceProvider provider = ClientServiceProvider.loadService();
    try (Producer ignored =
        provider
            .newProducerBuilder()
            .setClientConfiguration(clientConfiguration(endpoints))
            .setTopics(topic)
            .build()) {
      System.out.printf("PROXY_ROUTE_OK endpoint=%s topic=%s%n", endpoints, topic);
    }
  }

  private static void roundTrip(
      String endpoints, String topic, String consumerGroup, String messageKey) throws Exception {
    ClientServiceProvider provider = ClientServiceProvider.loadService();
    ClientConfiguration configuration = clientConfiguration(endpoints);
    FilterExpression filter = new FilterExpression(TAG, FilterExpressionType.TAG);
    String body = "cb013-probe:" + messageKey;

    try (SimpleConsumer consumer =
            provider
                .newSimpleConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(consumerGroup)
                .setAwaitDuration(RECEIVE_AWAIT)
                .setSubscriptionExpressions(Collections.singletonMap(topic, filter))
                .build();
        Producer producer =
            provider
                .newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(topic)
                .build()) {
      Message message =
          provider
              .newMessageBuilder()
              .setTopic(topic)
              .setTag(TAG)
              .setKeys(messageKey)
              .setBody(body.getBytes(StandardCharsets.UTF_8))
              .build();
      SendReceipt receipt = producer.send(message);
      System.out.printf(
          "PRODUCED endpoint=%s topic=%s key=%s messageId=%s%n",
          endpoints, topic, messageKey, receipt.getMessageId());

      MessageView consumed = receiveMatching(consumer, body);
      consumer.ack(consumed);
      System.out.printf(
          "CONSUMED topic=%s key=%s messageId=%s%n", topic, messageKey, consumed.getMessageId());
      System.out.printf("ACKNOWLEDGED messageId=%s%n", consumed.getMessageId());

      List<MessageView> secondDelivery = consumer.receive(8, INVISIBLE_DURATION);
      for (MessageView candidate : secondDelivery) {
        if (body.equals(body(candidate))) {
          throw new IllegalStateException(
              "Probe message was delivered more than once: " + candidate.getMessageId());
        }
      }
      System.out.printf(
          "ROUND_TRIP_OK endpoint=%s topic=%s key=%s produced=1 consumed=1%n",
          endpoints, topic, messageKey);
    }
  }

  private static MessageView receiveMatching(SimpleConsumer consumer, String expectedBody)
      throws Exception {
    long deadline = System.nanoTime() + ROUND_TRIP_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      for (MessageView message : consumer.receive(8, INVISIBLE_DURATION)) {
        if (expectedBody.equals(body(message))) {
          return message;
        }
      }
    }
    throw new IllegalStateException("Timed out waiting for the uniquely identified probe message");
  }

  private static String body(MessageView message) {
    ByteBuffer buffer = message.getBody();
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
