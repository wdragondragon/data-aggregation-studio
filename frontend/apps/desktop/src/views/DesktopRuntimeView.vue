<template>
  <div class="studio-page">
    <SectionCard title="Local Runtime Endpoints" description="Desktop keeps the runtime local and exposes the same API shape where possible.">
      <div class="endpoint-list">
        <div class="soft-panel">
          <strong>Renderer</strong>
          <p class="studio-mono">http://127.0.0.1:5174</p>
        </div>
        <div class="soft-panel">
          <strong>Desktop Runtime API</strong>
          <p class="studio-mono">http://127.0.0.1:18180/api/v1</p>
        </div>
        <div class="soft-panel">
          <strong>Knife4j</strong>
          <p class="studio-mono">http://127.0.0.1:18180/doc.html</p>
        </div>
      </div>
    </SectionCard>
  </div>
</template>

<script setup lang="ts">
import { SectionCard } from "@studio/ui";
</script>

<style scoped>
.endpoint-list {
  display: grid;
  gap: 14px;
}
</style>
