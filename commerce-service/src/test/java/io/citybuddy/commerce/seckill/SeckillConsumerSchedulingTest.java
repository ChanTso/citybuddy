package io.citybuddy.commerce.seckill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SeckillConsumerSchedulingTest {
  @Test
  void blockedConsumersDoNotStarveEachOtherOrDefaultRecoveryAndDispatch() throws Exception {
    BlockingReceives receives = new BlockingReceives();
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                TaskExecutionAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
        .withUserConfiguration(SeckillConsumerSchedulingConfiguration.class, Workers.class)
        .withBean(BlockingReceives.class, () -> receives)
        .withPropertyValues(
            "citybuddy.seckill.order.enabled=true",
            "citybuddy.seckill.order.worker-initial-delay-ms=0",
            "citybuddy.seckill.order.worker-delay-ms=10",
            "citybuddy.seckill.order.resolution-worker-initial-delay=0",
            "citybuddy.seckill.order.resolution-worker-delay=10",
            "citybuddy.seckill.timeout.consumer-worker-initial-delay-ms=0",
            "citybuddy.seckill.timeout.consumer-worker-delay-ms=10",
            "citybuddy.seckill.timeout.dispatch-worker-initial-delay-ms=0",
            "citybuddy.seckill.timeout.dispatch-worker-delay-ms=10")
        .run(
            context -> {
              try {
                assertThat(context).hasNotFailed();
                assertThat(receives.orderEntered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(receives.timeoutEntered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(receives.recoveryWhileBlocked.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(receives.dispatchWhileBlocked.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(receives.release.getCount()).isEqualTo(1);

                assertThat(context.getBean("taskScheduler", ThreadPoolTaskScheduler.class))
                    .extracting(ThreadPoolTaskScheduler::getPoolSize)
                    .isEqualTo(1);
                assertThat(context.getBean("applicationTaskExecutor"))
                    .isInstanceOf(ThreadPoolTaskExecutor.class);
                assertThat(receives.orderThread.get()).startsWith("seckill-order-");
                assertThat(receives.timeoutThread.get()).startsWith("seckill-timeout-");
                assertThat(receives.recoveryThread.get()).startsWith("scheduling-");
                assertThat(receives.dispatchThread.get()).isEqualTo(receives.recoveryThread.get());
              } finally {
                receives.release.countDown();
              }
            });
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableScheduling
  static class Workers {
    @Bean
    SeckillOrderWorker orderWorker(BlockingReceives receives) {
      return new SeckillOrderWorker(receives.orders, mock(SeckillOrderService.class));
    }

    @Bean
    SeckillTimeoutWorker timeoutWorker(BlockingReceives receives) {
      return new SeckillTimeoutWorker(
          receives.dispatch,
          receives.timeouts,
          mock(SeckillCancellationService.class),
          new SeckillTimeoutProperties(
              "proxy:8081", "timeouts", "consumer", null, null, null, null),
          Clock.systemUTC());
    }

    @Bean
    SeckillTransactionResolutionWorker recoveryWorker(BlockingReceives receives) {
      return new SeckillTransactionResolutionWorker(
          receives.reservations, mock(SeckillTransactionCoordinator.class));
    }
  }

  static final class BlockingReceives {
    final RocketMqSeckillTransactions orders = mock(RocketMqSeckillTransactions.class);
    final RocketMqSeckillTimeouts timeouts = mock(RocketMqSeckillTimeouts.class);
    final SeckillTimeoutDispatchService dispatch = mock(SeckillTimeoutDispatchService.class);
    final SeckillReservationService reservations = mock(SeckillReservationService.class);
    final CountDownLatch orderEntered = new CountDownLatch(1);
    final CountDownLatch timeoutEntered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch recoveryWhileBlocked = new CountDownLatch(1);
    final CountDownLatch dispatchWhileBlocked = new CountDownLatch(1);
    final AtomicReference<String> orderThread = new AtomicReference<>();
    final AtomicReference<String> timeoutThread = new AtomicReference<>();
    final AtomicReference<String> recoveryThread = new AtomicReference<>();
    final AtomicReference<String> dispatchThread = new AtomicReference<>();

    BlockingReceives() throws Exception {
      when(orders.consumeOnce(any()))
          .thenAnswer(
              invocation -> {
                orderThread.set(Thread.currentThread().getName());
                orderEntered.countDown();
                release.await();
                return 0;
              });
      when(timeouts.consumeOnce(any()))
          .thenAnswer(
              invocation -> {
                timeoutThread.set(Thread.currentThread().getName());
                timeoutEntered.countDown();
                release.await();
                return 0;
              });
      when(reservations.dueAdmissionHandoffs(anyInt()))
          .thenAnswer(
              invocation -> {
                if (bothConsumersEntered()) {
                  recoveryThread.set(Thread.currentThread().getName());
                  recoveryWhileBlocked.countDown();
                }
                return List.of();
              });
      when(dispatch.dispatchPreexistingOnce(any()))
          .thenReturn(new SeckillTimeoutDispatchService.DispatchBatch(0, 0, 0));
      when(dispatch.dispatchCurrentOnce())
          .thenAnswer(
              invocation -> {
                if (bothConsumersEntered()) {
                  dispatchThread.set(Thread.currentThread().getName());
                  dispatchWhileBlocked.countDown();
                }
                return new SeckillTimeoutDispatchService.DispatchBatch(0, 0, 0);
              });
    }

    private boolean bothConsumersEntered() {
      return orderEntered.getCount() == 0 && timeoutEntered.getCount() == 0;
    }
  }
}
