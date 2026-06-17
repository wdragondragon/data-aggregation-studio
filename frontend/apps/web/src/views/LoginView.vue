<template>
  <div class="login">
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
          <span v-for="item in scopeBadges" :key="item" class="login__badge">{{ item }}</span>
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

        <div class="login__scope-band">
          <div class="login__scope-copy">
            <span class="login__scope-title">{{ t("web.login.scopeTitle") }}</span>
            <p>{{ t("web.login.scopeDescription") }}</p>
          </div>
          <div class="login__scope-tags">
            <span v-for="item in scopeTags" :key="item">{{ item }}</span>
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

        <div class="login__gateway">
          <el-button
            type="primary"
            size="large"
            :loading="gatewayLoading"
            class="login__submit login__submit--gateway"
            @click="submitGateway"
          >
            {{ t("web.login.gatewayLogin") }}
          </el-button>
          <p class="login__gateway-tip">
            {{ t("web.login.gatewayTip") }}
          </p>
        </div>

        <div class="login__divider">
          <span>{{ t("web.login.localLoginDivider") }}</span>
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

        <div class="login__panel-footer">
          <p class="login__panel-note">{{ t("web.login.footerNote") }}</p>
          <el-button link type="primary" class="login__register-link" @click="router.push('/register')">
            {{ t("web.register.entry") }}
          </el-button>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import { getGatewayStudioEntryUrl, isGatewayStudioMode } from "@/api/studio";
import { useAuthStore } from "@/stores/auth";

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const { t } = useI18n();
const loading = ref(false);
const gatewayLoading = ref(false);
const form = reactive({
  username: "",
  password: "",
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
const scopeBadges = computed(() => [
  t("web.login.scopeBadgeAssets"),
  t("web.login.scopeBadgeIntegration"),
  t("web.login.scopeBadgeService"),
  t("web.login.scopeBadgeQuality"),
]);
const scopeTags = computed(() => [
  t("web.login.scopeDatasource"),
  t("web.login.scopeModel"),
  t("web.login.scopeCollection"),
  t("web.login.scopeWorkflow"),
  t("web.login.scopeDevelopment"),
  t("web.login.scopeOpenService"),
  t("web.login.scopeProtocol"),
  t("web.login.scopeQuality"),
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

async function submitGateway() {
  gatewayLoading.value = true;
  try {
    if (isGatewayStudioMode()) {
      await authStore.loginWithGateway();
      ElMessage.success(t("web.login.gatewaySuccess"));
      const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "/dashboard";
      router.push(redirect);
      return;
    }
    const entryUrl = getGatewayStudioEntryUrl();
    if (!entryUrl) {
      throw new Error(t("web.login.gatewayEntryMissing"));
    }
    window.location.assign(entryUrl);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.login.gatewayFailed"));
  } finally {
    gatewayLoading.value = false;
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
  border-radius: 8px;
  background:
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
  border-radius: 8px;
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
  border-radius: 8px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
  background: rgba(255, 255, 255, 0.1);
}

.login__hero-copy {
  display: grid;
  gap: 14px;
  max-width: 760px;
}

.login__hero-copy h2 {
  margin: 0;
  font-size: clamp(34px, 3.6vw, 42px);
  line-height: 1.12;
  letter-spacing: 0;
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
  border-radius: 8px;
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

.login__scope-band {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
  align-items: center;
  padding: 18px 20px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 8px;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.14), rgba(255, 255, 255, 0.08));
}

.login__scope-copy {
  min-width: 0;
}

.login__scope-title {
  display: inline-block;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.login__scope-copy p {
  margin: 0;
  font-size: 14px;
  line-height: 1.6;
  color: rgba(243, 248, 255, 0.78);
}

.login__scope-tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.login__scope-tags span {
  display: inline-flex;
  align-items: center;
  padding: 8px 10px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 8px;
  color: #f8fbff;
  background: rgba(255, 255, 255, 0.1);
  font-size: 12px;
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
  border-radius: 8px;
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
  border-radius: 8px;
  background: linear-gradient(180deg, rgba(237, 244, 255, 0.82), rgba(248, 251, 255, 0.9));
}

.login__trust-dot {
  flex: 0 0 auto;
  width: 11px;
  height: 11px;
  margin-top: 5px;
  border-radius: 50%;
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

.login__gateway {
  display: grid;
  gap: 10px;
  margin-bottom: 18px;
}

.login__gateway-tip,
.login__divider span {
  font-size: 13px;
  line-height: 1.6;
  color: var(--studio-text-soft);
}

.login__divider {
  position: relative;
  display: flex;
  justify-content: center;
  margin-bottom: 14px;
}

.login__divider::before {
  content: "";
  position: absolute;
  top: 50%;
  left: 0;
  right: 0;
  border-top: 1px solid rgba(64, 113, 187, 0.14);
}

.login__divider span {
  position: relative;
  padding: 0 10px;
  background: rgba(255, 255, 255, 0.88);
}

.login__form :deep(.el-form-item__label) {
  padding-bottom: 6px;
  font-size: 13px;
  font-weight: 700;
  color: #35516f;
}

.login__form :deep(.el-input__wrapper) {
  min-height: 50px;
  border-radius: 8px;
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

.login__submit--gateway {
  margin-top: 0;
}

.login__panel-note {
  margin: 16px 0 0;
}

.login__panel-footer {
  display: grid;
  gap: 10px;
  margin-top: 8px;
}

.login__register-link {
  justify-self: flex-start;
  padding: 0;
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
  .login__scope-band {
    grid-template-columns: minmax(0, 1fr);
  }

  .login__scope-tags {
    justify-content: flex-start;
  }

  .login__scope-tags span {
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
    border-radius: 8px;
    font-size: 18px;
  }
}
</style>
