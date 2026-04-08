<template>
  <div class="login">
    <div class="login__glow login__glow--primary" />
    <div class="login__glow login__glow--accent" />

    <section class="login__hero">
      <div class="login__hero-surface">
        <div class="login__brand">
          <div class="login__brand-mark">DA</div>
          <div class="login__brand-copy">
            <p class="login__eyebrow">{{ t("web.login.eyebrow") }}</p>
            <h1>{{ t("app.name") }}</h1>
          </div>
        </div>

        <div class="login__badges">
          <span class="login__badge">{{ t("shell.webRuntime") }}</span>
          <span class="login__badge login__badge--subtle">{{ t("web.login.runtimeBadge") }}</span>
        </div>

        <div class="login__hero-copy">
          <h2>{{ t("web.login.heroTitle") }}</h2>
          <p>{{ t("web.login.heroDescription") }}</p>
        </div>

        <div class="login__highlight-grid">
          <article v-for="item in highlights" :key="item.title" class="login__highlight">
            <span class="login__highlight-kicker">{{ item.kicker }}</span>
            <strong>{{ item.title }}</strong>
            <p>{{ item.description }}</p>
          </article>
        </div>

        <div class="login__credential-band">
          <div class="login__credential-copy">
            <span class="login__credential-title">{{ t("web.login.credentialTitle") }}</span>
            <p>{{ t("web.login.credentialHint") }}</p>
          </div>
          <div class="login__tips">
            <span v-for="item in credentials" :key="item.label">
              {{ item.label }} <strong class="studio-mono">{{ item.value }}</strong>
            </span>
          </div>
        </div>
      </div>
    </section>

    <section class="login__panel">
      <div class="login__card">
        <div class="login__panel-header">
          <p class="login__panel-kicker">{{ t("common.signIn") }}</p>
          <h3>{{ t("web.login.panelTitle") }}</h3>
          <p>{{ t("web.login.panelDescription") }}</p>
        </div>

        <div class="login__trust">
          <span class="login__trust-dot" />
          <div class="login__trust-copy">
            <strong>{{ t("web.login.accessTitle") }}</strong>
            <span>{{ t("web.login.accessDescription") }}</span>
          </div>
        </div>

        <el-form label-position="top" class="login__form" @submit.prevent="submit">
          <el-form-item :label="t('web.login.usernameLabel')">
            <el-input
              v-model="form.username"
              clearable
              size="large"
              :placeholder="t('web.login.usernamePlaceholder')"
              autocomplete="username"
            />
          </el-form-item>
          <el-form-item :label="t('web.login.passwordLabel')">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              size="large"
              :placeholder="t('web.login.passwordPlaceholder')"
              autocomplete="current-password"
            />
          </el-form-item>
          <el-button
            native-type="submit"
            type="primary"
            size="large"
            :loading="loading"
            class="login__submit"
          >
            {{ t("common.enterStudio") }}
          </el-button>
        </el-form>

        <p class="login__panel-note">{{ t("web.login.footerNote") }}</p>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import { useAuthStore } from "@/stores/auth";

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const { t } = useI18n();
const loading = ref(false);
const form = reactive({
  username: "admin",
  password: "admin123",
});
const highlights = computed(() => [
  {
    kicker: "01",
    title: t("web.login.highlightMetadataTitle"),
    description: t("web.login.highlightMetadataDescription"),
  },
  {
    kicker: "02",
    title: t("web.login.highlightWorkflowTitle"),
    description: t("web.login.highlightWorkflowDescription"),
  },
  {
    kicker: "03",
    title: t("web.login.highlightRuntimeTitle"),
    description: t("web.login.highlightRuntimeDescription"),
  },
]);
const credentials = computed(() => [
  { label: t("web.login.defaultAccount"), value: "admin" },
  { label: t("web.login.password"), value: "admin123" },
]);

async function submit() {
  loading.value = true;
  try {
    await authStore.login(form);
    ElMessage.success(t("web.login.success"));
    const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "/dashboard";
    router.push(redirect);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.login.failed"));
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(360px, 460px);
  gap: 28px;
  align-items: stretch;
  min-height: 100vh;
  padding: 28px;
  overflow: hidden;
}

.login__glow {
  position: absolute;
  z-index: 0;
  border-radius: 999px;
  filter: blur(24px);
  opacity: 0.72;
  pointer-events: none;
  animation: loginFloat 18s ease-in-out infinite alternate;
}

.login__glow--primary {
  top: -80px;
  right: 14%;
  width: 320px;
  height: 320px;
  background: rgba(37, 99, 235, 0.18);
}

.login__glow--accent {
  bottom: -100px;
  left: 8%;
  width: 360px;
  height: 360px;
  background: rgba(14, 165, 233, 0.14);
  animation-duration: 22s;
}

.login__hero,
.login__panel {
  position: relative;
  z-index: 1;
  min-width: 0;
}

.login__hero {
  display: flex;
}

.login__hero-surface {
  position: relative;
  display: grid;
  gap: 24px;
  width: 100%;
  min-width: 0;
  padding: clamp(34px, 4vw, 52px);
  overflow: hidden;
  border: 1px solid rgba(18, 73, 133, 0.18);
  border-radius: 36px;
  background:
    radial-gradient(circle at top right, rgba(106, 197, 255, 0.22), transparent 28%),
    radial-gradient(circle at bottom left, rgba(85, 160, 255, 0.14), transparent 24%),
    linear-gradient(145deg, #0f3463 0%, #154c83 48%, #1b6c9d 100%);
  box-shadow: 0 30px 60px rgba(15, 52, 99, 0.18);
  color: #f3f8ff;
}

.login__hero-surface::before {
  content: "";
  position: absolute;
  inset: 0;
  background:
    linear-gradient(rgba(255, 255, 255, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.06) 1px, transparent 1px);
  background-size: 34px 34px;
  mask-image: linear-gradient(135deg, rgba(0, 0, 0, 0.84), transparent 82%);
  pointer-events: none;
}

.login__brand {
  display: flex;
  align-items: center;
  gap: 16px;
}

.login__brand-mark {
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  border-radius: 22px;
  color: #0f3463;
  font-size: 20px;
  font-weight: 800;
  letter-spacing: 0.08em;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.96), rgba(225, 238, 255, 0.92));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.75);
}

.login__brand-copy h1 {
  margin: 0;
  font-size: clamp(28px, 4vw, 42px);
  line-height: 1.06;
}

.login__badges {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.login__badge {
  display: inline-flex;
  align-items: center;
  padding: 8px 14px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  background: rgba(255, 255, 255, 0.1);
}

.login__badge--subtle {
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: none;
  background: rgba(255, 255, 255, 0.06);
}

.login__hero-copy {
  display: grid;
  gap: 14px;
  max-width: 760px;
}

.login__hero-copy h2 {
  margin: 0;
  font-size: clamp(36px, 4.6vw, 58px);
  line-height: 1.04;
  letter-spacing: -0.03em;
}

.login__hero-copy p {
  margin: 0;
  max-width: 700px;
  font-size: 17px;
  line-height: 1.7;
  color: rgba(243, 248, 255, 0.84);
}

.login__eyebrow {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: rgba(243, 248, 255, 0.74);
}

.login__highlight-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.login__highlight {
  display: grid;
  gap: 8px;
  min-width: 0;
  padding: 18px 18px 20px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(14px);
}

.login__highlight-kicker {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.2em;
  color: rgba(255, 255, 255, 0.62);
}

.login__highlight strong {
  font-size: 17px;
  line-height: 1.3;
}

.login__highlight p {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: rgba(243, 248, 255, 0.76);
}

.login__credential-band {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
  align-items: center;
  padding: 18px 20px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 28px;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.14), rgba(255, 255, 255, 0.08));
}

.login__credential-copy {
  min-width: 0;
}

.login__credential-title {
  display: inline-block;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.login__credential-copy p {
  margin: 0;
  font-size: 14px;
  line-height: 1.6;
  color: rgba(243, 248, 255, 0.78);
}

.login__tips {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.login__tips span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 14px;
  border-radius: 999px;
  color: #f8fbff;
  background: rgba(255, 255, 255, 0.12);
  white-space: nowrap;
}

.login__panel {
  display: flex;
  align-items: center;
}

.login__card {
  position: relative;
  width: 100%;
  min-width: 0;
  padding: 34px;
  border: 1px solid rgba(64, 113, 187, 0.14);
  border-radius: 32px;
  background: rgba(255, 255, 255, 0.88);
  box-shadow:
    0 24px 50px rgba(37, 99, 235, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(18px);
}

.login__card::before {
  content: "";
  position: absolute;
  inset: 0;
  border-radius: inherit;
  padding: 1px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(123, 168, 235, 0.12));
  mask:
    linear-gradient(#fff 0 0) content-box,
    linear-gradient(#fff 0 0);
  mask-composite: exclude;
  pointer-events: none;
}

.login__panel-header {
  display: grid;
  gap: 10px;
}

.login__panel-kicker {
  margin: 0;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--studio-primary);
}

.login__panel-header h3 {
  margin: 0;
  font-size: 30px;
  line-height: 1.12;
}

.login__panel-header p {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--studio-text-soft);
}

.login__trust {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  margin-top: 22px;
  margin-bottom: 18px;
  padding: 14px 16px;
  border: 1px solid rgba(37, 99, 235, 0.1);
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(237, 244, 255, 0.82), rgba(248, 251, 255, 0.9));
}

.login__trust-dot {
  flex: 0 0 auto;
  width: 11px;
  height: 11px;
  margin-top: 5px;
  border-radius: 999px;
  background: linear-gradient(180deg, #1d4ed8, #38bdf8);
  box-shadow: 0 0 0 6px rgba(37, 99, 235, 0.12);
}

.login__trust-copy {
  display: grid;
  gap: 4px;
}

.login__trust-copy strong {
  font-size: 14px;
}

.login__trust-copy span,
.login__panel-note {
  font-size: 13px;
  line-height: 1.6;
  color: var(--studio-text-soft);
}

.login__form {
  margin-top: 4px;
}

.login__form :deep(.el-form-item__label) {
  padding-bottom: 6px;
  font-size: 13px;
  font-weight: 700;
  color: #35516f;
}

.login__form :deep(.el-input__wrapper) {
  min-height: 50px;
  border-radius: 16px;
  background: rgba(248, 251, 255, 0.98);
  box-shadow: 0 0 0 1px rgba(64, 113, 187, 0.12) inset;
}

.login__form :deep(.el-input__wrapper.is-focus) {
  box-shadow:
    0 0 0 1px rgba(37, 99, 235, 0.28) inset,
    0 0 0 4px rgba(37, 99, 235, 0.08);
}

.login__submit {
  width: 100%;
  min-height: 50px;
  margin-top: 10px;
  font-weight: 700;
  letter-spacing: 0.04em;
  box-shadow: 0 18px 28px rgba(37, 99, 235, 0.2);
}

.login__panel-note {
  margin: 16px 0 0;
}

@keyframes loginFloat {
  from {
    transform: translate3d(0, 0, 0) scale(1);
  }
  to {
    transform: translate3d(14px, -18px, 0) scale(1.06);
  }
}

@media (max-width: 1080px) {
  .login {
    grid-template-columns: minmax(0, 1fr);
    gap: 18px;
    padding: 18px;
  }

  .login__panel {
    order: -1;
  }

  .login__hero-surface,
  .login__card {
    padding: 24px;
  }

  .login__highlight-grid,
  .login__credential-band {
    grid-template-columns: minmax(0, 1fr);
  }

  .login__tips span {
    white-space: normal;
  }
}

@media (max-width: 720px) {
  .login__hero-copy h2 {
    font-size: 34px;
  }

  .login__brand {
    align-items: flex-start;
  }

  .login__brand-mark {
    width: 54px;
    height: 54px;
    border-radius: 18px;
    font-size: 18px;
  }
}
</style>
