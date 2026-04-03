<template>
  <section class="section-card">
    <header class="section-card__header">
      <div>
        <h3 class="section-card__title">{{ title }}</h3>
        <p v-if="description" class="section-card__description">{{ description }}</p>
      </div>
      <div v-if="$slots.actions" class="section-card__actions">
        <slot name="actions" />
      </div>
    </header>
    <div class="section-card__body">
      <slot />
    </div>
  </section>
</template>

<script setup lang="ts">
defineProps<{
  title: string;
  description?: string;
}>();
</script>

<style scoped>
.section-card {
  border: 1px solid var(--studio-border);
  border-radius: var(--studio-radius-xl);
  background: var(--studio-surface);
  backdrop-filter: blur(14px);
  box-shadow: var(--studio-shadow);
}

.section-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 22px 24px 0;
}

.section-card__title {
  margin: 0;
  font-size: 20px;
}

.section-card__description {
  margin: 8px 0 0;
  color: var(--studio-text-soft);
}

.section-card__actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.section-card__body {
  padding: 20px 24px 24px;
}
</style>
