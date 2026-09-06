package io.citybuddy.commerce.seckill;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.seckill.order.enabled", havingValue = "true")
public class SeckillConsumerSchedulingConfiguration {
  // Non-default candidates keep Boot's scheduler and application executor for unrelated work.
  @Bean(defaultCandidate = false)
  ThreadPoolTaskScheduler seckillOrderScheduler(ThreadPoolTaskSchedulerBuilder builder) {
    return builder.poolSize(1).threadNamePrefix("seckill-order-").build();
  }

  @Bean(defaultCandidate = false)
  ThreadPoolTaskScheduler seckillTimeoutScheduler(ThreadPoolTaskSchedulerBuilder builder) {
    return builder.poolSize(1).threadNamePrefix("seckill-timeout-").build();
  }
}
