import { createI18n } from "vue-i18n";
import { componentMessages } from "./messages/components";
import { desktopMessages } from "./messages/desktop";
import { sharedMessages } from "./messages/shared";
import { webMessages } from "./messages/web";

export const STUDIO_LOCALE_STORAGE_KEY = "studio.locale";
export const DEFAULT_STUDIO_LOCALE = "en-US";

export const studioMessages = {
  "en-US": {
    ...sharedMessages["en-US"],
    ...webMessages["en-US"],
    ...desktopMessages["en-US"],
    ...componentMessages["en-US"],
  },
  "zh-CN": {
    ...sharedMessages["zh-CN"],
    ...webMessages["zh-CN"],
    ...desktopMessages["zh-CN"],
    ...componentMessages["zh-CN"],
  },
} as const;

export type StudioLocale = keyof typeof studioMessages;

export interface StudioLocaleOption {
  value: StudioLocale;
  label: string;
}

export function resolveStudioLocale(locale?: string | null): StudioLocale {
  return locale === "zh-CN" ? "zh-CN" : DEFAULT_STUDIO_LOCALE;
}

export function readStoredStudioLocale(): StudioLocale {
  if (typeof window === "undefined") {
    return DEFAULT_STUDIO_LOCALE;
  }
  return resolveStudioLocale(window.localStorage.getItem(STUDIO_LOCALE_STORAGE_KEY));
}

export function persistStudioLocale(locale: StudioLocale) {
  if (typeof window === "undefined") {
    return;
  }
  window.localStorage.setItem(STUDIO_LOCALE_STORAGE_KEY, locale);
}

export function createStudioI18n() {
  return createI18n({
    legacy: false,
    locale: readStoredStudioLocale(),
    fallbackLocale: DEFAULT_STUDIO_LOCALE,
    messages: studioMessages,
  });
}

export function getStudioLocaleOptions(): StudioLocaleOption[] {
  return [
    { value: "en-US", label: "English" },
    { value: "zh-CN", label: "中文" },
  ];
}
