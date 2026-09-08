package io.citybuddy.commerce.seckill;

import org.springframework.scheduling.annotation.Scheduled;

public final class SeckillOrderWorker {
  private final RocketMqSeckillTransactions messaging;
  private final SeckillOrderService orders;

  public SeckillOrderWorker(RocketMqSeckillTransactions messaging, SeckillOrderService orders) {
    this.messaging = messaging;
    this.orders = orders;
  }

  // A fixed-delay task never overlaps itself; each method registers a separate bounded loop.
  @Scheduled(
      scheduler = "seckillOrderScheduler",
      initialDelayString = "${citybuddy.seckill.order.worker-initial-delay-ms:5000}",
      fixedDelayString = "${citybuddy.seckill.order.worker-delay-ms:10}")
  public int runOnce() throws Exception {
    return messaging.consumeOnce(orders);
  }

  @Scheduled(
      scheduler = "seckillOrderScheduler",
      initialDelayString = "${citybuddy.seckill.order.worker-initial-delay-ms:5000}",
      fixedDelayString = "${citybuddy.seckill.order.worker-delay-ms:10}")
  void consumeSecond() throws Exception {
    runOnce();
  }

  @Scheduled(
      scheduler = "seckillOrderScheduler",
      initialDelayString = "${citybuddy.seckill.order.worker-initial-delay-ms:5000}",
      fixedDelayString = "${citybuddy.seckill.order.worker-delay-ms:10}")
  void consumeThird() throws Exception {
    runOnce();
  }

  @Scheduled(
      scheduler = "seckillOrderScheduler",
      initialDelayString = "${citybuddy.seckill.order.worker-initial-delay-ms:5000}",
      fixedDelayString = "${citybuddy.seckill.order.worker-delay-ms:10}")
  void consumeFourth() throws Exception {
    runOnce();
  }
}
