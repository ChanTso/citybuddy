package io.citybuddy.commerce.evaluation;

import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.MultiValueMap;

final class EvaluationViewRequestParser {
  private static final Pattern BOUNDED_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]*$");
  private static final Set<String> AUDIT_PARAMETERS = Set.of("after", "limit");

  private EvaluationViewRequestParser() {}

  static String sandbox(String value) {
    return boundedId(value, 64, "Invalid evaluation sandbox");
  }

  static String session(String value) {
    return boundedId(value, 64, "Invalid support session");
  }

  static String trace(String value) {
    return boundedId(value, 64, "Invalid evaluation trace");
  }

  static String operation(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw invalid("Invalid evaluation operation");
    }
    return value;
  }

  static void requireNoParameters(MultiValueMap<String, String> parameters) {
    if (!parameters.isEmpty()) {
      throw invalid("Unknown evaluation parameter");
    }
  }

  static AuditPageRequest auditPage(MultiValueMap<String, String> parameters) {
    if (!AUDIT_PARAMETERS.containsAll(parameters.keySet())) {
      throw invalid("Unknown evaluation parameter");
    }
    String limitValue = single(parameters, "limit");
    String afterValue = single(parameters, "after");
    int limit = limitValue == null ? 20 : positiveInt(limitValue, 50, "Invalid audit limit");
    long after = afterValue == null ? 0 : positiveLong(afterValue, "Invalid audit cursor");
    return new AuditPageRequest(after, limit);
  }

  private static String single(MultiValueMap<String, String> parameters, String name) {
    if (!parameters.containsKey(name)) {
      return null;
    }
    if (parameters.get(name) == null || parameters.get(name).size() != 1) {
      throw invalid("Invalid evaluation parameter");
    }
    return parameters.getFirst(name);
  }

  private static int positiveInt(String value, int maximum, String message) {
    long parsed = positiveLong(value, message);
    if (parsed > maximum) {
      throw invalid(message);
    }
    return Math.toIntExact(parsed);
  }

  private static long positiveLong(String value, String message) {
    if (value == null || value.isBlank() || !value.matches("[1-9][0-9]*")) {
      throw invalid(message);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException exception) {
      throw invalid(message);
    }
  }

  private static String boundedId(String value, int maximum, String message) {
    if (value == null
        || value.isBlank()
        || value.length() > maximum
        || !BOUNDED_ID.matcher(value).matches()) {
      throw invalid(message);
    }
    return value;
  }

  private static EvaluationSandboxException invalid(String message) {
    return new EvaluationSandboxException(400, message);
  }

  record AuditPageRequest(long after, int limit) {}
}
