import type { EChartsOption } from "echarts";
import type { DataModelStatisticsChartView } from "@studio/api-sdk";

export type StatisticsChartGridMode = "default" | "wide" | "stacked";

export function buildTrendOption(chart: DataModelStatisticsChartView | undefined, countLabel: string): EChartsOption {
  if (!chart) {
    return {};
  }
  const series = chart.series?.[0];
  return {
    tooltip: { trigger: "axis" },
    grid: { left: 16, right: 16, top: 24, bottom: 24, containLabel: true },
    xAxis: {
      type: "category",
      data: chart.xAxis ?? [],
      axisLabel: { rotate: chart.xAxis.length > 10 ? 25 : 0 },
    },
    yAxis: {
      type: "value",
      minInterval: 1,
    },
    series: [{
      name: series?.name ?? countLabel,
      type: "line",
      smooth: true,
      areaStyle: { opacity: 0.18 },
      itemStyle: { color: "#2f6fed" },
      lineStyle: { width: 3 },
      data: (series?.data ?? []).map((item) => toNumber(item)),
    }],
  };
}

export function buildBarOption(chart: DataModelStatisticsChartView | undefined, countLabel: string): EChartsOption {
  if (!chart) {
    return {};
  }
  const series = chart.series?.[0];
  return {
    tooltip: { trigger: "axis" },
    grid: { left: 16, right: 16, top: 24, bottom: 48, containLabel: true },
    xAxis: {
      type: "category",
      data: chart.xAxis ?? [],
      axisLabel: { rotate: chart.xAxis.length > 6 ? 20 : 0 },
    },
    yAxis: {
      type: "value",
      minInterval: 1,
    },
    series: [{
      name: series?.name ?? countLabel,
      type: "bar",
      barMaxWidth: 42,
      itemStyle: { color: "#0f9d58", borderRadius: [8, 8, 0, 0] },
      data: (series?.data ?? []).map((item) => toNumber(item)),
    }],
  };
}

export function buildPieOption(
  chart: DataModelStatisticsChartView | undefined,
  layoutMode: StatisticsChartGridMode = "default",
  countLabel: string,
): EChartsOption {
  if (!chart) {
    return {};
  }
  const series = chart.series?.[0];
  const pieData = Array.isArray(series?.data)
    ? series.data.map((item, index) => {
      if (item && typeof item === "object" && "value" in item) {
        const pieItem = item as { name?: string; value?: unknown };
        return {
          name: pieItem.name ?? chart.xAxis[index] ?? `${index + 1}`,
          value: toNumber(pieItem.value),
        };
      }
      return {
        name: chart.xAxis[index] ?? `${index + 1}`,
        value: toNumber(item),
      };
    })
    : [];
  const largeLegend = pieData.length >= 12;
  const pieCenter = layoutMode === "stacked"
    ? (largeLegend ? ["50%", "32%"] : ["50%", "35%"])
    : layoutMode === "wide"
      ? ["50%", "38%"]
      : ["50%", "44%"];
  const pieRadius = layoutMode === "stacked"
    ? (largeLegend ? ["30%", "54%"] : ["34%", "58%"])
    : layoutMode === "wide"
      ? ["36%", "60%"]
      : ["40%", "68%"];
  return {
    tooltip: { trigger: "item" },
    legend: {
      bottom: 0,
      left: "center",
      width: "92%",
      itemGap: 14,
    },
    series: [{
      name: series?.name ?? countLabel,
      type: "pie",
      radius: pieRadius,
      center: pieCenter,
      itemStyle: {
        borderRadius: 8,
        borderColor: "#fff",
        borderWidth: 2,
      },
      label: {
        formatter: "{b}: {d}%",
      },
      data: pieData,
    }],
  };
}

function toNumber(value: unknown) {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : 0;
  }
  if (typeof value === "string") {
    const parsed = Number(value);
    return Number.isNaN(parsed) ? 0 : parsed;
  }
  return 0;
}
