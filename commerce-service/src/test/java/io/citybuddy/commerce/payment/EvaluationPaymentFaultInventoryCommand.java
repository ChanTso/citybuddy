package io.citybuddy.commerce.payment;

import java.util.Comparator;
import java.util.stream.Collectors;

/** Emits the production metadata consumed by the real MySQL integrity matrix. */
public final class EvaluationPaymentFaultInventoryCommand {
  private EvaluationPaymentFaultInventoryCommand() {}

  public static void main(String[] arguments) {
    if (arguments.length != 0) {
      throw new IllegalArgumentException("The payment fault inventory accepts no arguments");
    }
    EvaluationPaymentCommittedFaces.all().stream()
        .flatMap(
            face ->
                face.columnResponsibilities().entrySet().stream()
                    .map(entry -> new InventoryRow(face.name(), entry.getKey(), entry.getValue())))
        .sorted(
            Comparator.comparing(InventoryRow::face)
                .thenComparing(row -> row.column().table())
                .thenComparing(row -> row.column().column()))
        .forEach(
            row ->
                System.out.printf(
                    "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s%n",
                    row.face(),
                    row.column().table(),
                    row.column().column(),
                    row.responsibility().disposition(),
                    row.responsibility().correlatedGroup() == null
                        ? "-"
                        : row.responsibility().correlatedGroup(),
                    row.responsibility().applicableScopes().stream()
                        .map(Enum::name)
                        .sorted()
                        .collect(Collectors.joining(",")),
                    emptyMarker(row.responsibility().canonicalizerId()),
                    row.responsibility().anchorBindings().isEmpty()
                        ? "-"
                        : row.responsibility().anchorBindings().stream()
                            .map(
                                binding ->
                                    binding.root().table()
                                        + "."
                                        + binding.root().column()
                                        + "@"
                                        + binding.applicableScopes().stream()
                                            .map(Enum::name)
                                            .sorted()
                                            .collect(Collectors.joining(",")))
                            .sorted()
                            .collect(Collectors.joining(";"))));
    EvaluationPaymentCommittedFaces.orderOriginDefinitions().stream()
        .flatMap(
            origin ->
                origin.columns().stream()
                    .map(
                        column ->
                            new OriginRow(
                                origin,
                                column,
                                "intent_hash".equals(column) && !origin.canonicalizerId().isEmpty()
                                    ? EvaluationPaymentCommittedFaces.ContentDisposition
                                        .HASH_COMMITTED
                                    : EvaluationPaymentCommittedFaces.ContentDisposition
                                        .AUTHORITATIVE_ROOT)))
        .sorted(
            Comparator.comparing((OriginRow row) -> row.origin().name())
                .thenComparing(OriginRow::column))
        .forEach(
            row ->
                System.out.printf(
                    "order-origin:%s\t%s\t%s\t%s\t-\t-\t%s\t-%n",
                    row.origin().name(),
                    row.origin().table(),
                    row.column(),
                    row.disposition(),
                    emptyMarker(row.origin().canonicalizerId())));
  }

  private static String emptyMarker(String value) {
    return value.isEmpty() ? "-" : value;
  }

  private record InventoryRow(
      String face,
      EvaluationPaymentCommittedFaces.ColumnRef column,
      EvaluationPaymentCommittedFaces.ColumnResponsibility responsibility) {}

  private record OriginRow(
      EvaluationPaymentCommittedFaces.OrderOriginDefinition origin,
      String column,
      EvaluationPaymentCommittedFaces.ContentDisposition disposition) {}
}
