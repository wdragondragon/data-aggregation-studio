<template>
  <section class="section-card">
    <header class="section-card__header">
      <div>
        <h3 class="section-card__title">{{ title }}</h3>
        <p v-if="showDescription && description" class="section-card__description">{{ description }}</p>
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
withDefaults(defineProps<{
  title: string;
  description?: string;
  showDescription?: boolean;
}>(), {
  showDescription: false,
});
</script>

<style scoped>
.section-card {
  width: 100%;
  min-width: 0;
  max-width: 100%;
  border: 1px solid var(--studio-border);
  border-radius: var(--studio-radius-xl);
  background: var(--studio-surface);
  backdrop-filter: blur(14px);
  box-shadow: var(--studio-shadow);
}

.section-card__header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px 0;
  min-width: 0;
}

.section-card__title {
  margin: 0;
  font-size: 17px;
  line-height: 1.25;
}

.section-card__description {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--studio-text-soft);
}

.section-card__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
  max-width: 100%;
}

.section-card__body {
  padding: 14px 18px 18px;
  min-width: 0;
  max-width: 100%;
}
</style>
