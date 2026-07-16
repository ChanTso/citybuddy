package io.citybuddy.commerce.refund;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("citybuddy.refund")
public record RefundProperties(String requiredPermission) {
  public RefundProperties {
    requiredPermission =
        requiredPermission == null || requiredPermission.isBlank()
            ? "refund:create"
            : requiredPermission;
    if (requiredPermission.length() > 128
        || !requiredPermission.equals(requiredPermission.strip())) {
      throw new IllegalArgumentException("Refund permission is invalid");
    }
  }
}
