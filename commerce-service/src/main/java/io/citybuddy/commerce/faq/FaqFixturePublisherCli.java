package io.citybuddy.commerce.faq;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionException;

public final class FaqFixturePublisherCli {
  private static final int MAX_INPUT_BYTES = 1_048_576;
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private FaqFixturePublisherCli() {}

  public static void main(String[] args) {
    System.exit(run(args, System.in, System.out, System.err, System.getenv()));
  }

  static int run(
      String[] args,
      InputStream input,
      PrintStream output,
      PrintStream error,
      Map<String, String> environment) {
    if (args.length != 0) {
      return fail(error, "INVALID_ARGUMENTS", 2);
    }
    List<FaqFixturePublisher.Entry> entries;
    try {
      entries = readEntries(input);
    } catch (IOException | IllegalArgumentException exception) {
      return fail(error, "INVALID_INPUT", 2);
    } catch (FaqPublicationException exception) {
      return fail(error, exception.code().name(), 2);
    }
    String url = environment.get("SPRING_DATASOURCE_URL");
    String username = environment.get("SPRING_DATASOURCE_USERNAME");
    String password = environment.get("SPRING_DATASOURCE_PASSWORD");
    if (url == null
        || url.isBlank()
        || username == null
        || username.isBlank()
        || password == null
        || password.isBlank()) {
      return fail(error, "MISSING_DATABASE_CONFIGURATION", 2);
    }
    var dataSource = new DriverManagerDataSource(url, username, password);
    var repository = new FaqRepository(new JdbcTemplate(dataSource));
    var publication =
        new FaqPublicationService(repository, new FaqKnowledgeEventCodec(JSON), Clock.systemUTC());
    var publisher =
        new FaqFixturePublisher(
            repository, publication, new DataSourceTransactionManager(dataSource));
    try {
      output.println(JSON.writeValueAsString(publisher.publish(entries)));
      return 0;
    } catch (FaqPublicationException exception) {
      return fail(error, exception.code().name(), 3);
    } catch (DataAccessException | TransactionException exception) {
      // JDBC messages can contain connection details; this offline boundary emits only a category.
      return fail(error, "DATABASE_ERROR", 4);
    } catch (IOException exception) {
      return fail(error, "OUTPUT_ERROR", 5);
    }
  }

  static List<FaqFixturePublisher.Entry> readEntries(InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
    if (bytes.length > MAX_INPUT_BYTES) {
      throw new IllegalArgumentException("FAQ input exceeds the byte limit");
    }
    JsonNode root = JSON.readTree(bytes);
    if (root == null || !root.isArray()) {
      throw new IllegalArgumentException("FAQ input must be an array");
    }
    List<FaqFixturePublisher.Entry> entries = new ArrayList<>();
    for (JsonNode item : root) {
      if (!item.isObject()
          || item.size() != 3
          || !item.path("faqId").isTextual()
          || !item.path("question").isTextual()
          || !item.path("answer").isTextual()) {
        throw new IllegalArgumentException("FAQ entry requires exactly three text fields");
      }
      entries.add(
          new FaqFixturePublisher.Entry(
              item.get("faqId").textValue(),
              item.get("question").textValue(),
              item.get("answer").textValue()));
    }
    return List.copyOf(entries);
  }

  private static int fail(PrintStream error, String code, int status) {
    error.println("{\"error\":\"" + code + "\"}");
    return status;
  }
}
