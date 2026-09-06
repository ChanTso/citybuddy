package io.citybuddy.commerce.retail;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.catalog.ProductRepository;
import io.citybuddy.commerce.retail.RetailCatalogModels.Search;
import io.citybuddy.commerce.retail.RetailCatalogModels.View;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// Each class owns a database pool; release it before later integration applications start.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(
    classes = RetailCatalogPaginationIntegrationTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RetailCatalogPaginationIntegrationTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import(RetailCatalogConfiguration.class)
  static class Application {}

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("CATALOG_MYSQL_URL"));
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> required("MYSQL_COMMERCE_APP_PASSWORD"));
    registry.add("citybuddy.catalog.enabled", () -> "true");
  }

  @Autowired private RetailCatalogService catalog;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;
  @Autowired private PlatformTransactionManager transactionManager;
  private final List<String> productIds = new ArrayList<>();
  private final List<String> expectedRoots = new ArrayList<>();
  private String category;
  private String family;

  @BeforeEach
  void publishMoreThanOnePageOfRootsWithAFamilyAtThePageBoundary() throws Exception {
    category = "pagination-" + UUID.randomUUID().toString().substring(0, 8);
    family = category + "-family";
    var products = new ProductRepository(jdbc, mapper);
    var transactions = new TransactionTemplate(transactionManager);
    try (var connection =
        DriverManager.getConnection(
            required("CATALOG_MYSQL_URL"),
            "bootstrap_admin",
            required("MYSQL_BOOTSTRAP_PASSWORD"))) {
      var fixture = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      fixture.execute("SET ROLE 'bootstrap_grant_role'");
      fixture.update(
          """
          INSERT INTO retail_product_family
            (family_id,name,description,content,options,metadata_version,display_order)
          VALUES (?,?,'Pagination family',?, ?,1,97)
          """,
          family,
          family,
          mapper.writeValueAsString(Map.of("category", category)),
          "[{\"name\":\"size\",\"values\":[\"small\",\"medium\",\"large\"]}]");
      for (int index = 0; index < 52; index++) {
        String id = category + "-plain-" + String.format(java.util.Locale.ROOT, "%02d", index);
        publish(products, transactions, id, 1000 + index, 1);
        fixture.update(
            """
            INSERT INTO retail_product_metadata
              (product_id,family_id,content,option_values,metadata_version,display_order)
            VALUES (?,NULL,?,'{}',1,?)
            """,
            id,
            mapper.writeValueAsString(Map.of("category", category)),
            index * 2);
        expectedRoots.add(id);
      }
      expectedRoots.add(49, family);
      for (int index = 0; index < 3; index++) {
        String id = category + "-variant-" + index;
        publish(products, transactions, id, 2000 + index * 500, index + 1);
        fixture.update(
            """
            INSERT INTO retail_product_metadata
              (product_id,family_id,content,option_values,metadata_version,display_order)
            VALUES (?,?,'{}',?,1,?)
            """,
            id,
            family,
            mapper.writeValueAsString(
                Map.of("size", List.of("small", "medium", "large").get(index))),
            index);
      }
    }
  }

  @AfterEach
  void retireOnlyThisFixturesPublishedProducts() {
    // Other suites enumerate all published IDs; this fixture must not enlarge their cache work.
    for (String id : productIds) {
      jdbc.update(
          "UPDATE product SET publication_state='UNPUBLISHED' WHERE product_id = BINARY ?", id);
    }
  }

  @Test
  void rootPaginationReturnsAllRootsOnceAndKeepsEveryVariantUnderItsFamily() {
    List<View> first = catalog.search(search(50, 0));
    List<View> second = catalog.search(search(50, 50));
    assertThat(first).hasSize(50);
    assertThat(second).hasSize(3);
    List<String> combined = new ArrayList<>(first.stream().map(View::id).toList());
    combined.addAll(second.stream().map(View::id).toList());
    assertThat(combined).containsExactlyElementsOf(expectedRoots).doesNotHaveDuplicates();
    assertThat(catalog.search(search(50, 53))).isEmpty();
    assertThat(catalog.search(search(50, 10_000))).isEmpty();
    assertThat(catalog.search(search(50, 0))).isEqualTo(first);

    View summary = first.getLast();
    assertThat(summary.id()).isEqualTo(family);
    assertThat(summary.kind()).isEqualTo("family");
    assertThat(summary.priceMinor()).isEqualTo(2000);
    assertThat(summary.stockQuantity()).isEqualTo(6);
    assertThat(summary.variants()).isEmpty();
    assertThat(catalog.search(search(1, 49))).containsExactly(summary);
    View detail = catalog.find(family).orElseThrow();
    assertThat(detail.variants()).hasSize(3);
    assertThat(detail.variants())
        .allSatisfy(
            variant -> {
              assertThat(variant.variantOf()).isEqualTo(family);
              assertThat(variant.productId()).isNotIn(combined);
            });
    assertThat(detail.priceMinor()).isEqualTo(summary.priceMinor());
    assertThat(
            jdbc.queryForObject(
                """
        SELECT COUNT(*) FROM product p JOIN retail_product_metadata m ON m.product_id=p.product_id
        WHERE m.family_id=? AND p.publication_state='PUBLISHED'
        """,
                Integer.class,
                family))
        .isEqualTo(3);
  }

  private void publish(
      ProductRepository products,
      TransactionTemplate transactions,
      String id,
      long price,
      int stock) {
    productIds.add(id);
    transactions.executeWithoutResult(
        status ->
            products.publish(
                new ProductRepository.ProductDraft(
                    id, id, "Pagination fixture", price, "CNY", stock, true, true),
                UUID.randomUUID()));
  }

  private Search search(int limit, int offset) {
    return new Search(null, category, null, null, null, null, null, null, limit, offset);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing integration setting: " + name);
    }
    return value;
  }
}
