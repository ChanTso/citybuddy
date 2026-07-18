package io.citybuddy.commerce.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class EvaluationRequestParser {
  private static final Pattern BOUNDED_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]*$");
  private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
  private static final Set<String> RESET_FIELDS =
      Set.of("sandboxId", "caseCorrelation", "ttlSeconds", "testUserLabel", "products");
  private static final Set<String> PRODUCT_FIELDS =
      Set.of(
          "productId",
          "name",
          "description",
          "priceMinor",
          "currency",
          "stockQuantity",
          "available");
  private static final Set<String> COMPLETE_FIELDS = Set.of("caseCorrelation");

  private EvaluationRequestParser() {}

  public static EvaluationResetRequest parseReset(JsonNode body) {
    requireExactObject(body, RESET_FIELDS);
    String sandboxId = boundedId(body.get("sandboxId"), 64, "Invalid sandbox");
    String caseCorrelation =
        boundedId(body.get("caseCorrelation"), 128, "Invalid case correlation");
    String testUserLabel = boundedId(body.get("testUserLabel"), 128, "Invalid test user");
    int ttlSeconds = exactInt(body.get("ttlSeconds"), 60, 3600, "Invalid evaluation TTL");
    JsonNode productsNode = body.get("products");
    if (productsNode == null
        || !productsNode.isArray()
        || productsNode.isEmpty()
        || productsNode.size() > 16) {
      throw invalid("Invalid product fixtures");
    }
    List<EvaluationResetRequest.ProductFixture> products = new ArrayList<>();
    Set<String> productIds = new HashSet<>();
    for (JsonNode product : productsNode) {
      requireExactObject(product, PRODUCT_FIELDS);
      String productId = boundedId(product.get("productId"), 64, "Invalid product fixture");
      if (!productIds.add(productId)) {
        throw invalid("Duplicate product fixture");
      }
      String name = boundedText(product.get("name"), 200, "Invalid product fixture");
      String description = boundedText(product.get("description"), 1000, "Invalid product fixture");
      long priceMinor =
          exactLong(product.get("priceMinor"), 1, 1_000_000_000L, "Invalid product fixture");
      String currency = boundedText(product.get("currency"), 3, "Invalid product fixture");
      if (!CURRENCY.matcher(currency).matches()) {
        throw invalid("Invalid product fixture");
      }
      long stockQuantity =
          exactLong(product.get("stockQuantity"), 0, 1_000_000, "Invalid product fixture");
      JsonNode available = product.get("available");
      if (available == null || !available.isBoolean()) {
        throw invalid("Invalid product fixture");
      }
      products.add(
          new EvaluationResetRequest.ProductFixture(
              productId,
              name,
              description,
              priceMinor,
              currency,
              stockQuantity,
              available.booleanValue()));
    }
    products.sort(java.util.Comparator.comparing(EvaluationResetRequest.ProductFixture::productId));
    return new EvaluationResetRequest(
        sandboxId, caseCorrelation, ttlSeconds, testUserLabel, List.copyOf(products));
  }

  public static String parseCompletionCase(JsonNode body) {
    requireExactObject(body, COMPLETE_FIELDS);
    return boundedId(body.get("caseCorrelation"), 128, "Invalid case correlation");
  }

  public static String fixtureDigest(List<EvaluationResetRequest.ProductFixture> products) {
    StringBuilder canonical = new StringBuilder();
    for (EvaluationResetRequest.ProductFixture product : products) {
      append(canonical, product.productId());
      append(canonical, product.name());
      append(canonical, product.description());
      append(canonical, Long.toString(product.priceMinor()));
      append(canonical, product.currency());
      append(canonical, Long.toString(product.stockQuantity()));
      append(canonical, Boolean.toString(product.available()));
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public static String derivedIdempotencyKey(String purpose, String sandboxId, String caseId) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  (purpose + "\u0000" + sandboxId + "\u0000" + caseId)
                      .getBytes(StandardCharsets.UTF_8));
      return "cb101-"
          + purpose
          + "-"
          + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public static String boundedHeader(String value, int maximum, String message) {
    if (value == null
        || value.isBlank()
        || value.length() > maximum
        || !BOUNDED_ID.matcher(value).matches()) {
      throw invalid(message);
    }
    return value;
  }

  private static void requireExactObject(JsonNode node, Set<String> expected) {
    if (node == null || !node.isObject() || node.size() != expected.size()) {
      throw invalid("Invalid evaluation request");
    }
    Set<String> actual = new HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) {
      throw invalid("Invalid evaluation request");
    }
  }

  private static String boundedId(JsonNode node, int maximum, String message) {
    String value = boundedText(node, maximum, message);
    if (!BOUNDED_ID.matcher(value).matches()) {
      throw invalid(message);
    }
    return value;
  }

  private static String boundedText(JsonNode node, int maximum, String message) {
    if (node == null
        || !node.isTextual()
        || node.textValue().isBlank()
        || node.textValue().length() > maximum) {
      throw invalid(message);
    }
    return node.textValue();
  }

  private static int exactInt(JsonNode node, int minimum, int maximum, String message) {
    long value = exactLong(node, minimum, maximum, message);
    return Math.toIntExact(value);
  }

  private static long exactLong(JsonNode node, long minimum, long maximum, String message) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
      throw invalid(message);
    }
    long value = node.longValue();
    if (value < minimum || value > maximum) {
      throw invalid(message);
    }
    return value;
  }

  private static void append(StringBuilder builder, String value) {
    builder.append(value.length()).append(':').append(value).append(';');
  }

  private static EvaluationSandboxException invalid(String message) {
    return new EvaluationSandboxException(400, message);
  }
}
