import { createApp } from "vue";
import { createPinia } from "pinia";
import { installElementPlus } from "@studio/ui";
import App from "./App.vue";
import i18n from "./i18n";
import router from "./router";
import "./styles/app.css";

const app = createApp(App);

app.use(createPinia());
app.use(i18n);
app.use(router);
installElementPlus(app);
app.mount("#app");
