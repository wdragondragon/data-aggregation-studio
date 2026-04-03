<template>
  <div class="shell">
    <aside class="shell__sidebar" :class="{ 'shell__sidebar--open': mobileOpen }">
      <div class="shell__brand">
        <button class="shell__brand-mark" type="button" @click="emit('navigate', menus[0]?.path ?? '/')">
          DA
        </button>
        <div>
          <p class="shell__eyebrow">{{ resolvedModeLabel }}</p>
          <h1>{{ t("app.shellName") }}</h1>
        </div>
      </div>

      <nav class="shell__nav">
        <button
          v-for="item in menus"
          :key="item.path"
          type="button"
          class="shell__nav-item"
          :class="{ 'shell__nav-item--active': item.path === activePath }"
          @click="handleNavigate(item.path)"
        >
          <strong>{{ item.label }}</strong>
          <span v-if="item.caption">{{ item.caption }}</span>
        </button>
      </nav>

      <div class="shell__footer">
        <div class="shell__locale">
          <span>{{ t("common.language") }}</span>
          <div class="shell__locale-switch">
            <button
              v-for="option in localeOptions"
              :key="option.value"
              type="button"
              class="shell__locale-btn"
              :class="{ 'shell__locale-btn--active': option.value === locale }"
              @click="emit('locale-change', option.value)"
            >
              {{ option.label }}
            </button>
          </div>
        </div>
        <div class="shell__footer-copy">
          <strong>{{ resolvedTitle }}</strong>
          <span>{{ resolvedSubtitle }}</span>
        </div>
        <button class="shell__logout" type="button" @click="emit('logout')">{{ t("common.signOut") }}</button>
      </div>
    </aside>

    <div class="shell__backdrop" :class="{ 'shell__backdrop--open': mobileOpen }" @click="mobileOpen = false" />

    <main class="shell__main">
      <header class="shell__header">
        <button class="shell__menu-btn" type="button" @click="mobileOpen = !mobileOpen">
          {{ t("common.menu") }}
        </button>
        <div>
          <p class="shell__header-eyebrow">{{ resolvedModeLabel }}</p>
          <h2>{{ resolvedTitle }}</h2>
        </div>
        <p class="shell__subtitle">{{ resolvedSubtitle }}</p>
      </header>

      <div class="shell__content">
        <slot />
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import type { StudioLocaleOption, StudioNavItem } from "./types";

const props = defineProps<{
  menus: StudioNavItem[];
  activePath: string;
  title?: string;
  subtitle?: string;
  modeLabel?: string;
  locale: string;
  localeOptions: StudioLocaleOption[];
}>();

const emit = defineEmits<{
  navigate: [path: string];
  logout: [];
  "locale-change": [locale: string];
}>();

const { t } = useI18n();
const mobileOpen = ref(false);

const resolvedTitle = computed(() => props.title || t("app.name"));
const resolvedSubtitle = computed(() => props.subtitle || t("shell.defaultSubtitle"));
const resolvedModeLabel = computed(() => props.modeLabel || t("shell.webRuntime"));

function handleNavigate(path: string) {
  mobileOpen.value = false;
  emit("navigate", path);
}
</script>

<style scoped>
.shell {
  display: flex;
  min-height: 100vh;
}

.shell__sidebar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  flex-direction: column;
  gap: 26px;
  width: var(--studio-sidebar);
  min-height: 100vh;
  padding: 28px 22px;
  color: #f3f8ff;
  background:
    radial-gradient(circle at top right, rgba(56, 189, 248, 0.24), transparent 24%),
    linear-gradient(180deg, #17376a 0%, #0d2344 100%);
  box-shadow: 14px 0 40px rgba(13, 35, 68, 0.22);
}

.shell__brand {
  display: flex;
  gap: 14px;
  align-items: center;
}

.shell__brand h1,
.shell__header h2 {
  margin: 0;
}

.shell__brand-mark {
  width: 56px;
  height: 56px;
  border: 0;
  border-radius: 18px;
  color: #17376a;
  font-size: 16px;
  font-weight: 700;
  background: linear-gradient(135deg, #eff6ff, #ffffff);
  cursor: pointer;
}

.shell__eyebrow,
.shell__header-eyebrow {
  margin: 0 0 4px;
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  opacity: 0.72;
}

.shell__nav {
  display: grid;
  gap: 10px;
}

.shell__nav-item {
  display: grid;
  gap: 4px;
  padding: 14px 16px;
  border: 1px solid rgba(243, 248, 255, 0.08);
  border-radius: 18px;
  color: inherit;
  text-align: left;
  background: rgba(243, 248, 255, 0.04);
  cursor: pointer;
  transition: transform 0.2s ease, background-color 0.2s ease, border-color 0.2s ease;
}

.shell__nav-item span {
  font-size: 13px;
  opacity: 0.74;
}

.shell__nav-item:hover,
.shell__nav-item--active {
  transform: translateX(4px);
  border-color: rgba(125, 179, 255, 0.4);
  background: rgba(125, 179, 255, 0.14);
}

.shell__footer {
  margin-top: auto;
  display: grid;
  gap: 14px;
}

.shell__locale {
  display: grid;
  gap: 8px;
}

.shell__locale span {
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  opacity: 0.72;
}

.shell__locale-switch {
  display: flex;
  gap: 8px;
}

.shell__locale-btn {
  flex: 1;
  padding: 10px 12px;
  border: 1px solid rgba(243, 248, 255, 0.12);
  border-radius: 999px;
  color: inherit;
  background: rgba(243, 248, 255, 0.04);
  cursor: pointer;
  transition: background-color 0.2s ease, border-color 0.2s ease;
}

.shell__locale-btn--active {
  border-color: rgba(125, 179, 255, 0.44);
  background: rgba(125, 179, 255, 0.18);
}

.shell__footer-copy {
  display: grid;
  gap: 6px;
}

.shell__footer-copy span {
  font-size: 13px;
  opacity: 0.72;
}

.shell__logout,
.shell__menu-btn {
  padding: 10px 14px;
  border: 0;
  border-radius: 999px;
  color: inherit;
  background: rgba(243, 248, 255, 0.1);
  cursor: pointer;
}

.shell__main {
  flex: 1;
  min-width: 0;
  padding: 28px;
}

.shell__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 22px;
}

.shell__menu-btn {
  display: none;
  color: var(--studio-text);
  background: var(--studio-surface);
  box-shadow: var(--studio-shadow);
}

.shell__subtitle {
  max-width: 320px;
  margin: 0;
  color: var(--studio-text-soft);
  text-align: right;
}

.shell__content {
  display: grid;
  gap: 20px;
}

.shell__backdrop {
  display: none;
}

@media (max-width: 980px) {
  .shell__sidebar {
    position: fixed;
    left: 0;
    transform: translateX(-100%);
    transition: transform 0.24s ease;
  }

  .shell__sidebar--open {
    transform: translateX(0);
  }

  .shell__backdrop {
    position: fixed;
    inset: 0;
    z-index: 10;
    display: block;
    pointer-events: none;
    background: rgba(13, 35, 68, 0.28);
    opacity: 0;
    transition: opacity 0.24s ease;
  }

  .shell__backdrop--open {
    opacity: 1;
    pointer-events: auto;
  }

  .shell__main {
    padding: 18px;
  }

  .shell__header {
    align-items: flex-start;
    flex-direction: column;
  }

  .shell__menu-btn {
    display: inline-flex;
  }

  .shell__subtitle {
    text-align: left;
  }
}
</style>
