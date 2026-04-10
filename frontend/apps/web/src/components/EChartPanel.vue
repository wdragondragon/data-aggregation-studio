<template>
  <div ref="containerRef" class="chart-panel" :style="{ height }" />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts";
import type { ECharts, EChartsOption } from "echarts";

const props = withDefaults(defineProps<{
  option?: EChartsOption;
  height?: string;
}>(), {
  option: undefined,
  height: "320px",
});

const containerRef = ref<HTMLDivElement>();
let chart: ECharts | undefined;
let resizeObserver: ResizeObserver | undefined;

function renderChart() {
  if (!containerRef.value) {
    return;
  }
  if (!chart) {
    chart = echarts.init(containerRef.value);
  }
  chart.setOption(props.option ?? {}, true);
  chart.resize();
}

function handleResize() {
  chart?.resize();
}

onMounted(() => {
  renderChart();
  resizeObserver = new ResizeObserver(handleResize);
  if (containerRef.value) {
    resizeObserver.observe(containerRef.value);
  }
  window.addEventListener("resize", handleResize);
});

watch(() => props.option, () => {
  renderChart();
}, { deep: true });

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  window.removeEventListener("resize", handleResize);
  chart?.dispose();
  chart = undefined;
});
</script>

<style scoped>
.chart-panel {
  width: 100%;
  min-height: 220px;
}
</style>
