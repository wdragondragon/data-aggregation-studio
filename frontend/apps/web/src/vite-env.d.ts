/// <reference types="vite/client" />

declare module "no-vue3-cron" {
  import type { App, Component } from "vue";

  export const noVue3Cron: Component;

  const plugin: {
    install: (app: App) => void;
  };

  export default plugin;
}
