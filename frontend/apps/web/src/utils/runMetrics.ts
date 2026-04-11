import type { ComposerTranslation } from "vue-i18n";
import type { RunMetricSummary } from "@studio/api-sdk";

export type RunMetricLabelKey =
  | "collectedRecords"
  | "successRecords"
  | "failedRecords"
  | "transformerTotalRecords"
  | "transformerSuccessRecords"
  | "transformerFailedRecords"
  | "transformerFilterRecords";

export function metricLabel(t: ComposerTranslation, key: RunMetricLabelKey) {
  switch (key) {
    case "collectedRecords":
      return t("web.runMetrics.metricCollected");
    case "successRecords":
      return t("web.runMetrics.metricSuccess");
    case "failedRecords":
      return t("web.runMetrics.metricFailed");
    case "transformerTotalRecords":
      return t("web.runMetrics.metricTransformerTotal");
    case "transformerSuccessRecords":
      return t("web.runMetrics.metricTransformerSuccess");
    case "transformerFailedRecords":
      return t("web.runMetrics.metricTransformerFailed");
    case "transformerFilterRecords":
      return t("web.runMetrics.metricFiltered");
    default:
      return key;
  }
}

export function metricSummaryValue(summary: RunMetricSummary | undefined, key: RunMetricLabelKey) {
  if (!summary) {
    return 0;
  }
  switch (key) {
    case "collectedRecords":
      return toMetricNumber(summary.collectedRecords);
    case "successRecords":
      return toMetricNumber(summary.successRecords ?? (toMetricNumber(summary.readSucceedRecords) - toMetricNumber(summary.writeFailedRecords)));
    case "failedRecords":
      return toMetricNumber(summary.failedRecords);
    case "transformerTotalRecords":
      return toMetricNumber(summary.transformerTotalRecords);
    case "transformerSuccessRecords":
      return toMetricNumber(summary.transformerSuccessRecords);
    case "transformerFailedRecords":
      return toMetricNumber(summary.transformerFailedRecords);
    case "transformerFilterRecords":
      return toMetricNumber(summary.transformerFilterRecords);
    default:
      return 0;
  }
}

export function toMetricNumber(value: unknown) {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "string") {
    const numeric = Number(value);
    return Number.isFinite(numeric) ? numeric : 0;
  }
  return 0;
}

export function formatMetricNumber(value: unknown) {
  return toMetricNumber(value).toLocaleString();
}
