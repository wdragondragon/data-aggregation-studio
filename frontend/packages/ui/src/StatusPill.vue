<template>
  <span class="status-pill" :class="`status-pill--${tone}`">
    {{ label }}
  </span>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string;
    tone?: "primary" | "success" | "warning" | "danger" | "neutral";
  }>(),
  {
    tone: "neutral",
  },
);
</script>

<style scoped>
.status-pill {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.status-pill--primary {
  color: var(--studio-primary-deep);
  background: rgba(37, 99, 235, 0.12);
}

.status-pill--success {
  color: var(--studio-success);
  background: rgba(15, 118, 110, 0.12);
}

.status-pill--warning {
  color: var(--studio-warning);
  background: rgba(217, 119, 6, 0.14);
}

.status-pill--danger {
  color: var(--studio-danger);
  background: rgba(220, 38, 38, 0.12);
}

.status-pill--neutral {
  color: var(--studio-text-soft);
  background: rgba(91, 116, 153, 0.12);
}
</style>
