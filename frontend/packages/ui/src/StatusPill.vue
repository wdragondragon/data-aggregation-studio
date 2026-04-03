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
  background: rgba(184, 92, 56, 0.14);
}

.status-pill--success {
  color: var(--studio-success);
  background: rgba(47, 122, 83, 0.14);
}

.status-pill--warning {
  color: var(--studio-warning);
  background: rgba(176, 114, 27, 0.14);
}

.status-pill--danger {
  color: var(--studio-danger);
  background: rgba(173, 66, 54, 0.14);
}

.status-pill--neutral {
  color: var(--studio-text-soft);
  background: rgba(101, 80, 63, 0.12);
}
</style>
