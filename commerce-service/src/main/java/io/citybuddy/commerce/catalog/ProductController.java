package io.citybuddy.commerce.catalog;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "citybuddy.catalog.enabled", havingValue = "true")
public final class ProductController {
  private final DirectUserAuthorizer authorizer;
  private final ProductCatalogService catalog;

  public ProductController(DirectUserAuthorizer authorizer, ProductCatalogService catalog) {
    this.authorizer = authorizer;
    this.catalog = catalog;
  }

  @GetMapping("/api/products")
  public List<Product> products(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String evalSandbox) {
    authorizer.authorize(authorization, evalSandbox);
    return catalog.listPublished();
  }

  @GetMapping("/api/products/{productId}")
  public Product product(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String evalSandbox,
      @PathVariable String productId) {
    authorizer.authorize(authorization, evalSandbox);
    return catalog
        .findPublished(productId)
        .orElseThrow(() -> new CatalogException(404, "Product not found"));
  }
}
