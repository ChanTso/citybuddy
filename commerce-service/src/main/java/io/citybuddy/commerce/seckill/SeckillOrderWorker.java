package io.citybuddy.commerce.seckill;

import org.springframework.scheduling.annotation.Scheduled;

public final class SeckillOrderWorker {
  private final RocketMqSeckillTransactions messaging;
  private final SeckillOrderService orders;

  public SeckillOrderWorker(RocketMqSeckillTransactions messaging, SeckillOrderService orders) {
    this.messaging = messaging;
    this.orders = orders;
  }

  @Scheduled(
      initialDelayString = "${citybuddy.seckill.order.worker-initial-delay-ms:5000}",
      fixedDelayString = "${citybuddy.seckill.order.worker-delay-ms:1000}")
  public int runOnce() throws Exception {
    return messaging.consumeOnce(orders);
  }
}
