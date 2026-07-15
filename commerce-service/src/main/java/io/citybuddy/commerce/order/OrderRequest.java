package io.citybuddy.commerce.order;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OrderRequest {
  private String productId;
  private Integer quantity;
  private Long expectedProductVersion;
  private final Map<String, Object> extraFields = new LinkedHashMap<>();

  public String getProductId() {
    return productId;
  }

  public void setProductId(String productId) {
    this.productId = productId;
  }

  public Integer getQuantity() {
    return quantity;
  }

  public void setQuantity(Integer quantity) {
    this.quantity = quantity;
  }

  public Long getExpectedProductVersion() {
    return expectedProductVersion;
  }

  public void setExpectedProductVersion(Long expectedProductVersion) {
    this.expectedProductVersion = expectedProductVersion;
  }

  @JsonAnySetter
  public void extraField(String name, Object value) {
    extraFields.put(name, value);
  }

  public Map<String, Object> extraFields() {
    return Map.copyOf(extraFields);
  }
}
