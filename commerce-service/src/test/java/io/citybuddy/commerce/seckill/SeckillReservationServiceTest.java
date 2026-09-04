package io.citybuddy.commerce.seckill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionTemplate;

class SeckillReservationServiceTest {
  @Test
  void transactionCheckerReturnsUnknownWhenMysqlCannotBeRead() {
    SeckillReservationRepository repository = mock(SeckillReservationRepository.class);
    when(repository.find("00000000-0000-0000-0000-000000000001"))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));
    SeckillReservationService service =
        new SeckillReservationService(
            repository,
            mock(SeckillActivityRepository.class),
            mock(ReservationAdmissionStore.class),
            mock(TransactionTemplate.class),
            mock(SeckillReservationProperties.class));

    assertThat(service.transactionResolution("00000000-0000-0000-0000-000000000001"))
        .isEqualTo(TransactionResolution.UNKNOWN);
  }
}
