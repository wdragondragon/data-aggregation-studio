<template>
  <div class="shell" :class="{ 'shell--collapsed': desktopCollapsed }">
    <aside class="shell__sidebar" :class="{ 'shell__sidebar--open': mobileOpen }">
      <div class="shell__brand">
        <button class="shell__brand-mark" type="button" @click="emit('navigate', defaultEntryPath)">
          DA
        </button>
        <div v-if="!desktopCollapsed" class="shell__brand-copy">
          <p class="shell__eyebrow">{{ resolvedModeLabel }}</p>
          <h1>{{ t("app.shellName") }}</h1>
        </div>
        <button
          class="shell__collapse-btn"
          type="button"
          :title="desktopCollapsed ? t('common.expandSidebar') : t('common.collapseSidebar')"
          :aria-label="desktopCollapsed ? t('common.expandSidebar') : t('common.collapseSidebar')"
          @click="toggleSidebarCollapse"
        >
          {{ desktopCollapsed ? ">>" : "<<" }}
        </button>
      </div>

      <nav class="shell__nav">
        <template v-for="item in menus" :key="resolveItemKey(item)">
          <div v-if="item.children?.length" class="shell__nav-group" :class="{ 'shell__nav-group--active': groupHasActiveChild(item) }">
            <button
              type="button"
              class="shell__nav-group-toggle"
              :class="{ 'shell__nav-group-toggle--active': groupHasActiveChild(item) }"
              :title="desktopCollapsed ? item.label : undefined"
              @click="toggleGroup(item)"
            >
              <div class="shell__nav-group-copy">
                <strong>{{ desktopCollapsed ? compactLabel(item.label) : item.label }}</strong>
                <span v-if="item.caption && !desktopCollapsed">{{ item.caption }}</span>
              </div>
              <span v-if="!desktopCollapsed" class="shell__nav-group-arrow">{{ isGroupExpanded(item) ? "v" : ">" }}</span>
            </button>

            <div v-if="isGroupExpanded(item)" class="shell__nav-group-children">
              <button
                v-for="child in item.children"
                :key="resolveItemKey(child)"
                type="button"
                class="shell__nav-subitem"
                :class="{ 'shell__nav-subitem--active': child.path === activePath }"
                :title="desktopCollapsed ? child.label : undefined"
                @click="handleNavigate(child.path)"
              >
                <strong>{{ desktopCollapsed ? compactLabel(child.label) : child.label }}</strong>
                <span v-if="child.caption && !desktopCollapsed">{{ child.caption }}</span>
              </button>
            </div>
          </div>

          <button
            v-else
            type="button"
            class="shell__nav-item"
            :class="{ 'shell__nav-item--active': item.path === activePath }"
            :title="desktopCollapsed ? item.label : undefined"
            @click="handleNavigate(item.path)"
          >
            <strong>{{ desktopCollapsed ? compactLabel(item.label) : item.label }}</strong>
            <span v-if="item.caption && !desktopCollapsed">{{ item.caption }}</span>
          </button>
        </template>
      </nav>

      <div v-if="$slots['sidebar-context'] && !desktopCollapsed" class="shell__context">
        <slot name="sidebar-context" />
      </div>

      <div class="shell__footer">
        <div v-if="!desktopCollapsed" class="shell__locale">
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
        <div v-if="!desktopCollapsed" class="shell__footer-copy">
          <strong>{{ resolvedTitle }}</strong>
          <span>{{ resolvedSubtitle }}</span>
        </div>
        <button class="shell__logout" type="button" :title="t('common.signOut')" @click="emit('logout')">
          {{ desktopCollapsed ? compactLabel(t("common.signOut")) : t("common.signOut") }}
        </button>
      </div>
    </aside>

    <div class="shell__backdrop" :class="{ 'shell__backdrop--open': mobileOpen }" @click="mobileOpen = false" />

    <main class="shell__main">
      <header class="shell__header">
        <div class="shell__header-main">
          <button class="shell__menu-btn" type="button" @click="mobileOpen = !mobileOpen">
            {{ t("common.menu") }}
          </button>
          <div>
            <p class="shell__header-eyebrow">{{ resolvedModeLabel }}</p>
            <h2>{{ resolvedTitle }}</h2>
          </div>
        </div>
        <div v-if="$slots['header-actions']" class="shell__header-actions">
          <slot name="header-actions" />
        </div>
        <p class="shell__subtitle">{{ resolvedSubtitle }}</p>
      </header>

      <div
        v-loading="loading"
        class="shell__content"
        :element-loading-text="t('common.loading')"
        element-loading-background="rgba(248, 250, 252, 0.52)"
      >
        <slot />
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import type { StudioLocaleOption, StudioNavItem } from "./types";

const DESKTOP_COLLAPSED_STORAGE_KEY = "studio.shell.desktopCollapsed";

const props = defineProps<{
  menus: StudioNavItem[];
  activePath: string;
  loading?: boolean;
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
const desktopCollapsed = ref(readDesktopCollapsedPreference());
const expandedGroups = ref<Record<string, boolean>>({});
const defaultEntryPath = computed(() => firstNavigablePath(props.menus) ?? "/dashboard");

const resolvedTitle = computed(() => props.title || t("app.name"));
const resolvedSubtitle = computed(() => props.subtitle || t("shell.defaultSubtitle"));
const resolvedModeLabel = computed(() => props.modeLabel || t("shell.webRuntime"));

watch(desktopCollapsed, (value) => {
  persistDesktopCollapsedPreference(value);
});

function handleNavigate(path?: string) {
  if (!path) {
    return;
  }
  mobileOpen.value = false;
  emit("navigate", path);
}

function toggleSidebarCollapse() {
  if (typeof window !== "undefined" && window.innerWidth <= 980) {
    mobileOpen.value = !mobileOpen.value;
    return;
  }
  desktopCollapsed.value = !desktopCollapsed.value;
}

function toggleGroup(item: StudioNavItem) {
  if (!item.children?.length) {
    handleNavigate(item.path);
    return;
  }
  const key = resolveItemKey(item);
  expandedGroups.value = {
    ...expandedGroups.value,
    [key]: !isGroupExpanded(item),
  };
}

function isGroupExpanded(item: StudioNavItem) {
  if (!item.children?.length) {
    return false;
  }
  const key = resolveItemKey(item);
  return expandedGroups.value[key] === true;
}

function groupHasActiveChild(item: StudioNavItem) {
  return item.children?.some((child) => child.path === props.activePath) ?? false;
}

function resolveItemKey(item: StudioNavItem) {
  return item.key ?? item.path ?? item.label;
}

function compactLabel(label: string) {
  const trimmed = label.trim();
  if (!trimmed) {
    return "--";
  }
  if (/[\u4e00-\u9fff]/.test(trimmed)) {
    return trimmed.slice(0, 2);
  }
  const words = trimmed.split(/\s+/).filter(Boolean);
  if (words.length >= 2) {
    return words.slice(0, 2).map((word) => word.slice(0, 1)).join("").toUpperCase();
  }
  return trimmed.slice(0, 2).toUpperCase();
}

function readDesktopCollapsedPreference() {
  if (typeof window === "undefined") {
    return false;
  }
  return window.localStorage.getItem(DESKTOP_COLLAPSED_STORAGE_KEY) === "true";
}

function persistDesktopCollapsedPreference(value: boolean) {
  if (typeof window === "undefined") {
    return;
  }
  window.localStorage.setItem(DESKTOP_COLLAPSED_STORAGE_KEY, String(value));
}

function firstNavigablePath(items: StudioNavItem[]): string | null {
  for (const item of items) {
    if (item.path) {
      return item.path;
    }
    if (item.children?.length) {
      const childPath = firstNavigablePath(item.children);
      if (childPath) {
        return childPath;
      }
    }
  }
  return null;
}
</script>

<style scoped>
.shell {
  --shell-sidebar-width: var(--studio-sidebar);
  display: flex;
  min-height: 100vh;
}

.shell--collapsed {
  --shell-sidebar-width: 96px;
}

.shell__sidebar {
  position: sticky;
  top: 0;
  z-index: 20;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 18px;
  width: var(--shell-sidebar-width);
  min-height: 100vh;
  padding: 20px 16px;
  color: #f3f8ff;
  overflow-x: hidden;
  overflow-y: auto;
  transition: width 0.24s ease, padding 0.24s ease;
  background:
    radial-gradient(circle at top right, rgba(56, 189, 248, 0.24), transparent 24%),
    linear-gradient(180deg, #17376a 0%, #0d2344 100%);
  box-shadow: 14px 0 40px rgba(13, 35, 68, 0.22);
}

.shell--collapsed .shell__sidebar {
  padding-left: 10px;
  padding-right: 10px;
}

.shell__brand {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  min-width: 0;
}

.shell--collapsed .shell__brand {
  justify-content: center;
}

.shell__brand-copy {
  min-width: 0;
  flex: 1;
}

.shell__brand h1,
.shell__header h2 {
  margin: 0;
}

.shell__brand > div {
  min-width: 0;
}

.shell__brand h1 {
  font-size: clamp(30px, 2.2vw, 40px);
  line-height: 1.05;
  letter-spacing: -0.03em;
  word-break: keep-all;
}

.shell__brand-mark {
  flex-shrink: 0;
  width: 48px;
  height: 48px;
  border: 0;
  border-radius: 14px;
  color: #17376a;
  font-size: 15px;
  font-weight: 700;
  background: linear-gradient(135deg, #eff6ff, #ffffff);
  cursor: pointer;
}

.shell__collapse-btn {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  margin-left: auto;
  border: 0;
  border-radius: 12px;
  color: inherit;
  background: rgba(243, 248, 255, 0.1);
  cursor: pointer;
}

.shell--collapsed .shell__collapse-btn {
  margin-left: 0;
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
  min-width: 0;
}

.shell__nav-group {
  display: grid;
  gap: 8px;
}

.shell__nav-group-toggle,
.shell__nav-item {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 11px 13px;
  border: 1px solid rgba(243, 248, 255, 0.08);
  border-radius: 14px;
  color: inherit;
  text-align: left;
  background: rgba(243, 248, 255, 0.04);
  cursor: pointer;
  transition: transform 0.2s ease, background-color 0.2s ease, border-color 0.2s ease;
}

.shell__nav-group-toggle {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
}

.shell--collapsed .shell__nav-item {
  justify-items: center;
  text-align: center;
  padding: 12px 10px;
}

.shell--collapsed .shell__nav-group-toggle {
  grid-template-columns: 1fr;
  justify-items: center;
  text-align: center;
  padding: 12px 10px;
}

.shell__nav-group-copy {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.shell__nav-item strong,
.shell__nav-item span,
.shell__nav-group-toggle strong,
.shell__nav-group-toggle span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.shell__nav-item span,
.shell__nav-group-toggle span {
  font-size: 12px;
  opacity: 0.74;
}

.shell__nav-group-arrow {
  font-size: 12px;
  opacity: 0.72;
}

.shell__nav-item:hover,
.shell__nav-item--active,
.shell__nav-group-toggle:hover,
.shell__nav-group-toggle--active {
  transform: translateX(4px);
  border-color: rgba(125, 179, 255, 0.4);
  background: rgba(125, 179, 255, 0.14);
}

.shell--collapsed .shell__nav-item:hover,
.shell--collapsed .shell__nav-item--active,
.shell--collapsed .shell__nav-group-toggle:hover,
.shell--collapsed .shell__nav-group-toggle--active {
  transform: none;
}

.shell__nav-group-children {
  display: grid;
  gap: 6px;
  padding-left: 12px;
  border-left: 1px solid rgba(243, 248, 255, 0.12);
}

.shell--collapsed .shell__nav-group-children {
  padding-left: 0;
  border-left: 0;
}

.shell__nav-subitem {
  display: grid;
  gap: 3px;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid rgba(243, 248, 255, 0.06);
  border-radius: 12px;
  color: inherit;
  text-align: left;
  background: rgba(243, 248, 255, 0.025);
  cursor: pointer;
  transition: transform 0.2s ease, background-color 0.2s ease, border-color 0.2s ease;
}

.shell__nav-subitem strong,
.shell__nav-subitem span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.shell__nav-subitem span {
  font-size: 12px;
  opacity: 0.72;
}

.shell__nav-subitem:hover,
.shell__nav-subitem--active {
  transform: translateX(4px);
  border-color: rgba(125, 179, 255, 0.34);
  background: rgba(125, 179, 255, 0.12);
}

.shell--collapsed .shell__nav-subitem {
  justify-items: center;
  text-align: center;
  padding: 10px 8px;
}

.shell--collapsed .shell__nav-subitem:hover,
.shell--collapsed .shell__nav-subitem--active {
  transform: none;
}

.shell__context {
  display: grid;
  gap: 10px;
  width: 100%;
  min-width: 0;
  padding: 12px;
  border: 1px solid rgba(243, 248, 255, 0.08);
  border-radius: 14px;
  background: rgba(243, 248, 255, 0.04);
}

.shell__footer {
  margin-top: auto;
  display: grid;
  gap: 10px;
  min-width: 0;
  width: 100%;
}

.shell__locale {
  display: grid;
  gap: 8px;
  min-width: 0;
  width: 100%;
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
  min-width: 0;
  width: 100%;
}

.shell__locale-btn {
  flex: 1;
  min-width: 0;
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
  min-width: 0;
  width: 100%;
}

.shell__footer-copy strong,
.shell__footer-copy span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.shell__footer-copy span {
  font-size: 12px;
  opacity: 0.72;
}

.shell__logout,
.shell__menu-btn {
  padding: 9px 12px;
  border: 0;
  border-radius: 999px;
  color: inherit;
  background: rgba(243, 248, 255, 0.1);
  cursor: pointer;
}

.shell__main {
  flex: 1;
  min-width: 0;
  padding: 20px;
  overflow-x: hidden;
}

.shell__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 14px;
}

.shell__header-main {
  display: flex;
  gap: 14px;
  align-items: center;
  min-width: 0;
}

.shell__header-actions {
  display: flex;
  flex: 1;
  justify-content: flex-end;
  min-width: 0;
}

.shell__menu-btn {
  display: none;
  color: var(--studio-text);
  background: var(--studio-surface);
  box-shadow: var(--studio-shadow);
}

.shell--collapsed .shell__logout {
  padding-left: 0;
  padding-right: 0;
}

.shell__subtitle {
  display: none;
}

.shell__content {
  display: grid;
  gap: 20px;
  min-width: 0;
  max-width: 100%;
  overflow-x: hidden;
}

.shell__backdrop {
  display: none;
}

@media (max-width: 980px) {
  .shell,
  .shell--collapsed {
    --shell-sidebar-width: var(--studio-sidebar);
  }

  .shell__sidebar {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    height: 100dvh;
    max-height: 100dvh;
    padding-bottom: calc(20px + env(safe-area-inset-bottom, 0px));
    overscroll-behavior-y: contain;
    -webkit-overflow-scrolling: touch;
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
    padding: 14px;
  }

  .shell__header {
    align-items: flex-start;
    flex-direction: column;
  }

  .shell__header-main,
  .shell__header-actions {
    width: 100%;
  }

  .shell__header-actions {
    justify-content: flex-start;
  }

  .shell__menu-btn {
    display: inline-flex;
  }

  .shell__collapse-btn {
    display: none;
  }

  .shell__subtitle {
    text-align: left;
  }
}
</style>
