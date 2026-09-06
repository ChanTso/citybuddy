package io.citybuddy.commerce.retail;

import java.time.Instant;

public final class RetailPolicyModels {
  private RetailPolicyModels() {}

  public record Policy(
      String policyId,
      String title,
      String category,
      String content,
      long publicationVersion,
      Instant publishedAt) {}
}
