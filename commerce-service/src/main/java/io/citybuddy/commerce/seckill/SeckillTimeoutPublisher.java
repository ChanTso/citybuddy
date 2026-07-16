package io.citybuddy.commerce.seckill;

import org.apache.rocketmq.client.apis.ClientException;

@FunctionalInterface
public interface SeckillTimeoutPublisher {
  String send(SeckillTimeoutMessage message) throws ClientException;
}
