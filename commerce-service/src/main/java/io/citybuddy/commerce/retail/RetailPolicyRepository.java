package io.citybuddy.commerce.retail;

import io.citybuddy.commerce.retail.RetailPolicyModels.Policy;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public final class RetailPolicyRepository {
  private final JdbcTemplate jdbc;

  public RetailPolicyRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Policy> search(String query) {
    if (query == null || query.isBlank() || query.length() > 200) {
      throw new IllegalArgumentException("query must contain between 1 and 200 characters");
    }
    String[] words = query.strip().split("\\s+");
    if (words.length > 8) {
      throw new IllegalArgumentException("query must contain at most eight words");
    }
    List<String> scores = new ArrayList<>();
    List<Object> arguments = new ArrayList<>();
    for (String word : words) {
      String pattern = "%" + word.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
      scores.add(
          "(CASE WHEN published_question LIKE ? ESCAPE '!' THEN 3 ELSE 0 END"
              + " + CASE WHEN published_answer LIKE ? ESCAPE '!' THEN 1 ELSE 0 END)");
      arguments.add(pattern);
      arguments.add(pattern);
    }
    return jdbc.query(
        "SELECT faq_id, published_question, published_answer, published_version, published_at, "
            + String.join(" + ", scores)
            + " AS score FROM faq_source WHERE published_version > 0"
            + " AND (faq_id LIKE 'retail-policy-%' OR faq_id LIKE 'retail-guide-%')"
            + " HAVING score > 0 ORDER BY score DESC, faq_id ASC LIMIT 3",
        (row, index) ->
            new Policy(
                row.getString("faq_id"),
                row.getString("published_question"),
                null,
                row.getString("published_answer"),
                row.getLong("published_version"),
                row.getTimestamp("published_at").toInstant()),
        arguments.toArray());
  }
}
