<template>
  <main class="auth-handoff" aria-live="polite">
    <div class="auth-handoff__brand" aria-label="DataAggregation Studio">
      <span class="auth-handoff__mark">DA</span>
      <span>DataAggregation Studio</span>
    </div>

    <section class="auth-handoff__status">
      <el-icon v-if="!errorMessage" class="auth-handoff__spinner" :size="28">
        <Loading />
      </el-icon>
      <el-icon v-else class="auth-handoff__error-icon" :size="28">
        <WarningFilled />
      </el-icon>
      <h1>{{ errorMessage ? t("web.authHandoff.failedTitle") : t("web.authHandoff.title") }}</h1>
      <p>{{ errorMessage || t("web.authHandoff.description") }}</p>
      <el-button v-if="errorMessage" type="primary" @click="returnToStudio">
        {{ t("web.authHandoff.returnToStudio") }}
      </el-button>
    </section>
  </main>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Loading, WarningFilled } from "@element-plus/icons-vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import { resolveHandoffQueryValue, resolveSameOriginReturnPath } from "@/utils/authHandoff";

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const { t } = useI18n();
const errorMessage = ref("");

onMounted(() => {
  if (!authStore.isAuthenticated) {
    errorMessage.value = t("web.authHandoff.authenticationFailed");
    return;
  }

  const fallbackPath = router.resolve("/guide").href;
  const returnPath = resolveSameOriginReturnPath(
    resolveHandoffQueryValue(route.query),
    window.location.origin,
    fallbackPath,
  );

  if (returnPath === route.fullPath || returnPath === router.resolve("/auth/handoff").href) {
    window.location.replace(fallbackPath);
    return;
  }
  window.location.replace(returnPath);
});

function returnToStudio() {
  window.location.replace(router.resolve("/guide").href);
}
</script>

<style scoped>
.auth-handoff {
  display: grid;
  grid-template-rows: auto 1fr;
  min-height: 100vh;
  padding: 24px 28px;
  color: #172033;
  background: #f5f7fa;
}

.auth-handoff__brand {
  display: inline-flex;
  gap: 10px;
  align-items: center;
  width: fit-content;
  color: #25354d;
  font-size: 15px;
  font-weight: 650;
}

.auth-handoff__mark {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 6px;
  color: #ffffff;
  background: #1668dc;
  font-size: 13px;
}

.auth-handoff__status {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-width: 0;
  padding: 24px;
  text-align: center;
}

.auth-handoff__status h1 {
  margin: 16px 0 8px;
  font-size: 22px;
  line-height: 1.35;
  letter-spacing: 0;
}

.auth-handoff__status p {
  max-width: 520px;
  margin: 0 0 20px;
  color: #66758c;
  font-size: 14px;
  line-height: 1.7;
}

.auth-handoff__spinner {
  color: #1668dc;
  animation: auth-handoff-spin 0.9s linear infinite;
}

.auth-handoff__error-icon {
  color: #d94b4b;
}

@keyframes auth-handoff-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 640px) {
  .auth-handoff {
    padding: 18px;
  }

  .auth-handoff__status {
    padding: 18px 0;
  }
}
</style>
