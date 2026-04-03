<template>
  <div class="login">
    <div class="login__hero">
      <p class="login__eyebrow">{{ t("web.login.eyebrow") }}</p>
      <h1>{{ t("web.login.heroTitle") }}</h1>
      <p>{{ t("web.login.heroDescription") }}</p>
      <div class="login__tips">
        <span>{{ t("web.login.defaultAccount") }} <strong class="studio-mono">admin</strong></span>
        <span>{{ t("web.login.password") }} <strong class="studio-mono">admin123</strong></span>
      </div>
    </div>

    <div class="login__card">
      <h2>{{ t("common.signIn") }}</h2>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item :label="t('web.login.usernameLabel')">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item :label="t('web.login.passwordLabel')">
          <el-input v-model="form.password" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-button type="primary" :loading="loading" class="login__submit" @click="submit">
          {{ t("common.enterStudio") }}
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
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
  display: grid;
  grid-template-columns: 1.2fr 420px;
  gap: 24px;
  min-height: 100vh;
  padding: 32px;
}

.login__hero,
.login__card {
  border: 1px solid var(--studio-border);
  border-radius: 32px;
  box-shadow: var(--studio-shadow);
}

.login__hero {
  padding: 48px;
  color: #f3f8ff;
  background:
    radial-gradient(circle at top left, rgba(125, 179, 255, 0.28), transparent 30%),
    linear-gradient(180deg, #1d4f91 0%, #0e2c57 100%);
}

.login__hero h1 {
  margin: 0;
  font-size: clamp(34px, 5vw, 58px);
  line-height: 1.02;
}

.login__hero p {
  max-width: 720px;
  font-size: 17px;
  color: rgba(243, 248, 255, 0.82);
}

.login__eyebrow {
  margin: 0 0 16px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.login__tips {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 28px;
}

.login__tips span {
  padding: 10px 14px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
}

.login__card {
  align-self: center;
  padding: 32px;
  background: rgba(255, 255, 255, 0.92);
}

.login__card h2 {
  margin-top: 0;
  margin-bottom: 18px;
}

.login__submit {
  width: 100%;
  margin-top: 8px;
}

@media (max-width: 1080px) {
  .login {
    grid-template-columns: minmax(0, 1fr);
    padding: 18px;
  }

  .login__hero,
  .login__card {
    padding: 24px;
  }
}
</style>
