package io.citybuddy.commerce.seckill;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ReservationRequest {
  private Integer quantity;
  private Long expectedActivityVersion;
  private final Map<String, Object> extraFields = new LinkedHashMap<>();

  public Integer getQuantity() {
    return quantity;
  }

  public void setQuantity(Integer quantity) {
    this.quantity = quantity;
  }

  public Long getExpectedActivityVersion() {
    return expectedActivityVersion;
  }

  public void setExpectedActivityVersion(Long expectedActivityVersion) {
    this.expectedActivityVersion = expectedActivityVersion;
  }

  @JsonAnySetter
  public void captureExtra(String name, Object value) {
    extraFields.put(name, value);
  }

  public Map<String, Object> extraFields() {
    return Map.copyOf(extraFields);
  }
}
