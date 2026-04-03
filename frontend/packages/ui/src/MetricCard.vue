<template>
  <article class="metric-card" :class="toneClass">
    <div class="metric-card__meta">
      <span class="metric-card__label">{{ label }}</span>
      <span v-if="hint" class="metric-card__hint">{{ hint }}</span>
    </div>
    <strong class="metric-card__value">{{ value }}</strong>
    <p v-if="description" class="metric-card__description">{{ description }}</p>
  </article>
</template>

<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{
  label: string;
  value: string | number;
  description?: string;
  hint?: string;
  tone?: "primary" | "accent" | "success" | "warning";
}>();

const toneClass = computed(() => `metric-card--${props.tone ?? "primary"}`);
</script>

<style scoped>
.metric-card {
  position: relative;
  overflow: hidden;
  padding: 22px;
  border: 1px solid var(--studio-border);
  border-radius: var(--studio-radius-lg);
  background: linear-gradient(180deg, rgba(247, 251, 255, 0.96), rgba(255, 255, 255, 0.9));
  box-shadow: var(--studio-shadow);
}

.metric-card::after {
  content: "";
  position: absolute;
  inset: auto -32px -48px auto;
  width: 130px;
  height: 130px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.46);
}

.metric-card__meta {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.metric-card__label {
  font-size: 13px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--studio-text-soft);
}

.metric-card__hint {
  font-size: 12px;
  color: var(--studio-text-soft);
}

.metric-card__value {
  display: block;
  margin-top: 18px;
  font-size: 40px;
  line-height: 1;
}

.metric-card__description {
  margin: 12px 0 0;
  color: var(--studio-text-soft);
}

.metric-card--primary {
  background: linear-gradient(180deg, rgba(232, 242, 255, 0.98), rgba(255, 255, 255, 0.92));
}

.metric-card--accent {
  background: linear-gradient(180deg, rgba(229, 248, 255, 0.98), rgba(255, 255, 255, 0.92));
}

.metric-card--success {
  background: linear-gradient(180deg, rgba(232, 247, 245, 0.98), rgba(255, 255, 255, 0.92));
}

.metric-card--warning {
  background: linear-gradient(180deg, rgba(255, 246, 231, 0.98), rgba(255, 255, 255, 0.92));
}
</style>
