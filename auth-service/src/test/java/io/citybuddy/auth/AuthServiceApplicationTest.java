package io.citybuddy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(properties = "spring.main.banner-mode=off")
class AuthServiceApplicationTest {
  @Autowired private ApplicationContext applicationContext;

  @Test
  void loadsTheAuthServiceApplicationContext() {
    assertThat(applicationContext.getBean(AuthServiceApplication.class)).isNotNull();
  }
}
