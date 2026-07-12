package io.citybuddy.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(properties = "spring.main.banner-mode=off")
class CommerceServiceApplicationTest {
  @Autowired private ApplicationContext applicationContext;

  @Test
  void loadsTheCommerceServiceApplicationContext() {
    assertThat(applicationContext.getBean(CommerceServiceApplication.class)).isNotNull();
  }
}
