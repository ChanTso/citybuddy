package io.citybuddy.commerce.retail;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.citybuddy.commerce.catalog.CatalogException;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import io.citybuddy.commerce.retail.RetailCatalogModels.Search;
import io.citybuddy.commerce.retail.RetailCatalogModels.View;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "citybuddy.catalog.enabled", havingValue = "true")
public final class RetailCatalogController {
  private static final int MAXIMUM_BODY_BYTES = 8192;
  private final DirectUserAuthorizer authorizer;
  private final RetailCatalogService catalog;
  private final ObjectReader searchReader;

  public RetailCatalogController(
      DirectUserAuthorizer authorizer, RetailCatalogService catalog, ObjectMapper mapper) {
    this.authorizer = authorizer;
    this.catalog = catalog;
    this.searchReader =
        mapper
            .readerFor(Search.class)
            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
  }

  @GetMapping("/api/retail/products")
  public List<View> products(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String evalSandbox) {
    authorizer.authorize(authorization, evalSandbox, "catalog:read");
    return catalog.search(defaultSearch());
  }

  @PostMapping("/api/retail/products/search")
  public List<View> search(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String evalSandbox,
      HttpServletRequest request) {
    authorizer.authorize(authorization, evalSandbox, "catalog:read");
    Search search;
    try {
      byte[] body = request.getInputStream().readNBytes(MAXIMUM_BODY_BYTES + 1);
      if (body.length > MAXIMUM_BODY_BYTES) {
        throw new CatalogException(413, "Search request is too large");
      }
      search = searchReader.readValue(body);
    } catch (IOException | IllegalArgumentException exception) {
      throw new CatalogException(400, "Invalid retail search");
    }
    if (search == null) {
      throw new CatalogException(400, "Invalid retail search");
    }
    return catalog.search(search);
  }

  @GetMapping("/api/retail/products/{id}")
  public View product(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String evalSandbox,
      @PathVariable String id) {
    authorizer.authorize(authorization, evalSandbox, "catalog:read");
    if (id.length() > 64 || id.isBlank()) {
      throw new CatalogException(400, "Invalid retail product id");
    }
    return catalog
        .find(id)
        .orElseThrow(() -> new CatalogException(404, "Retail product not found"));
  }

  private static Search defaultSearch() {
    return new Search(null, null, null, null, null, null, null, null, null);
  }
}
