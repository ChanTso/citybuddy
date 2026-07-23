package io.citybuddy.commerce.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class KnowledgeSnapshotService {
  private static final String SCHEMA_VERSION = "cb113-v1";
  private static final Pattern SOURCE_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
  private static final Pattern UUID_PATTERN =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

  private final KnowledgeSnapshotRepository repository;
  private final KnowledgeSnapshotProperties properties;
  private final Clock clock;

  public KnowledgeSnapshotService(
      KnowledgeSnapshotRepository repository, KnowledgeSnapshotProperties properties, Clock clock) {
    this.repository = repository;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> capture() {
    List<Map<String, Object>> records = new ArrayList<>();
    int queryLimit = properties.maximumRecords() + 1;
    repository.publishedFaqs(queryLimit).forEach(source -> records.add(record(source)));
    repository.publishedProducts(queryLimit).forEach(source -> records.add(record(source)));
    records.sort(
        Comparator.comparing((Map<String, Object> record) -> (String) record.get("sourceId"))
            .thenComparing(record -> (String) record.get("chunkId")));
    if (records.isEmpty() || records.size() > properties.maximumRecords()) {
      throw new KnowledgeSnapshotException(409, "invalid_snapshot_cardinality");
    }
    if (records.stream()
            .map(record -> record.get("sourceId") + ":" + record.get("chunkId"))
            .distinct()
            .count()
        != records.size()) {
      throw new KnowledgeSnapshotException(409, "duplicate_snapshot_identity");
    }

    List<Map<String, Object>> sourceStates = sourceStates(records);
    String contentCommitment = digest(records);
    String watermark = digest(sourceStates);
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("schemaVersion", SCHEMA_VERSION);
    snapshot.put("snapshotId", stableSnapshotId(contentCommitment, watermark));
    snapshot.put("capturedAt", DateTimeFormatter.ISO_INSTANT.format(clock.instant()));
    snapshot.put("recordCount", records.size());
    snapshot.put("sourceCount", sourceStates.size());
    snapshot.put("contentCommitment", contentCommitment);
    snapshot.put("watermark", watermark);
    snapshot.put("records", records);
    return snapshot;
  }

  private static Map<String, Object> record(KnowledgeSnapshotRepository.PublishedSource source) {
    if (source.eventId() == null
        || !UUID_PATTERN.matcher(source.eventId()).matches()
        || source.sourceId() == null
        || !SOURCE_ID.matcher(source.sourceId()).matches()
        || source.sourceVersion() < 1
        || source.occurredTime() == null
        || !validText(source.title(), 500)
        || !validText(source.content(), 4000)
        || !(source.docType().equals("faq") || source.docType().equals("product"))
        || (source.docType().equals("faq") && !source.chunkId().equals("answer"))
        || (source.docType().equals("product") && !source.chunkId().equals("description"))) {
      throw new KnowledgeSnapshotException(409, "invalid_published_source");
    }
    Map<String, Object> metadata = new TreeMap<>();
    metadata.put("category", source.category());
    metadata.put("language", source.language());
    if (source.docType().equals("product")) {
      metadata.put("productId", source.sourceId());
    }
    Map<String, Object> record = new TreeMap<>();
    record.put("eventId", source.eventId());
    record.put("sourceId", source.sourceId());
    record.put("sourceVersion", source.sourceVersion());
    record.put("chunkId", source.chunkId());
    record.put("docType", source.docType());
    record.put("publicationState", "PUBLISHED");
    record.put("tombstone", false);
    record.put("occurredTime", DateTimeFormatter.ISO_INSTANT.format(source.occurredTime()));
    record.put("title", source.title());
    record.put("content", source.content());
    record.put("publicMetadata", metadata);
    return record;
  }

  private static List<Map<String, Object>> sourceStates(List<Map<String, Object>> records) {
    Map<String, List<Map<String, Object>>> grouped = new TreeMap<>();
    for (Map<String, Object> record : records) {
      grouped
          .computeIfAbsent((String) record.get("sourceId"), ignored -> new ArrayList<>())
          .add(record);
    }
    List<Map<String, Object>> states = new ArrayList<>();
    for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
      List<Map<String, Object>> sourceRecords = entry.getValue();
      Map<String, Object> first = sourceRecords.getFirst();
      if (sourceRecords.stream().anyMatch(record -> !sameSourceState(first, record))) {
        throw new KnowledgeSnapshotException(409, "conflicting_snapshot_source");
      }
      List<String> commitments =
          sourceRecords.stream()
              .sorted(Comparator.comparing(record -> (String) record.get("chunkId")))
              .map(KnowledgeSnapshotService::digest)
              .toList();
      Map<String, Object> state = new TreeMap<>();
      state.put("sourceId", entry.getKey());
      state.put("sourceVersion", first.get("sourceVersion"));
      state.put("docType", first.get("docType"));
      state.put("tombstone", first.get("tombstone"));
      state.put("eventId", first.get("eventId"));
      state.put("occurredTime", first.get("occurredTime"));
      state.put("recordCommitments", commitments);
      states.add(state);
    }
    return states;
  }

  private static boolean sameSourceState(Map<String, Object> first, Map<String, Object> other) {
    return List.of("sourceVersion", "docType", "tombstone", "eventId", "occurredTime").stream()
        .allMatch(field -> first.get(field).equals(other.get(field)));
  }

  private static boolean validText(String value, int maximumCodeUnits) {
    return value != null && !value.isBlank() && value.length() <= maximumCodeUnits;
  }

  static String digest(Object value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] encoded = CanonicalJson.encode(value).getBytes(StandardCharsets.UTF_8);
      return java.util.HexFormat.of().formatHex(digest.digest(encoded));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String stableSnapshotId(String contentCommitment, String watermark) {
    return UUID.nameUUIDFromBytes(
            (contentCommitment + ":" + watermark).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private static final class CanonicalJson {
    private CanonicalJson() {}

    static String encode(Object value) {
      if (value == null) {
        return "null";
      }
      if (value instanceof String text) {
        return quote(text);
      }
      if (value instanceof Boolean || value instanceof Number) {
        return value.toString();
      }
      if (value instanceof List<?> values) {
        return values.stream()
            .map(CanonicalJson::encode)
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
      }
      if (value instanceof Map<?, ?> values) {
        return values.entrySet().stream()
            .map(entry -> Map.entry((String) entry.getKey(), entry.getValue()))
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> quote(entry.getKey()) + ":" + encode(entry.getValue()))
            .collect(java.util.stream.Collectors.joining(",", "{", "}"));
      }
      throw new KnowledgeSnapshotException(409, "invalid_snapshot_value");
    }

    private static String quote(String value) {
      StringBuilder result = new StringBuilder(value.length() + 2).append('"');
      for (int offset = 0; offset < value.length(); offset++) {
        char character = value.charAt(offset);
        switch (character) {
          case '"' -> result.append("\\\"");
          case '\\' -> result.append("\\\\");
          case '\b' -> result.append("\\b");
          case '\f' -> result.append("\\f");
          case '\n' -> result.append("\\n");
          case '\r' -> result.append("\\r");
          case '\t' -> result.append("\\t");
          default -> {
            if (character < 0x20) {
              result.append(String.format("\\u%04x", (int) character));
            } else {
              result.append(character);
            }
          }
        }
      }
      return result.append('"').toString();
    }
  }
}
