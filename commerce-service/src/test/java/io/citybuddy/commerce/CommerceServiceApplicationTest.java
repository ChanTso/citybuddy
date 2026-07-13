package io.citybuddy.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class CommerceServiceApplicationTest {
  @Autowired private ApplicationContext applicationContext;

  @Test
  void loadsTheCommerceServiceApplicationContext() {
    assertThat(applicationContext.getBean(CommerceServiceApplication.class)).isNotNull();
    assertThat(applicationContext.getEnvironment().getActiveProfiles()).containsExactly("test");
    assertThat(applicationContext.getEnvironment().getProperty("spring.application.name"))
        .isEqualTo("commerce-service-test");
  }
}
