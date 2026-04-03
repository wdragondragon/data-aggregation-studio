<template>
  <div class="login">
    <div class="login__panel">
      <p class="login__eyebrow">{{ t("desktop.login.eyebrow") }}</p>
      <h1>{{ t("desktop.login.heroTitle") }}</h1>
      <p>{{ t("desktop.login.heroDescription") }}</p>
    </div>

    <div class="login__card">
      <h2>{{ t("routes.desktop.login.title") }}</h2>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item :label="t('desktop.login.usernameLabel')">
          <el-input v-model="form.username" />
        </el-form-item>
        <el-form-item :label="t('desktop.login.passwordLabel')">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-button type="primary" class="login__submit" :loading="loading" @click="submit">
          {{ t("common.openRuntime") }}
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import { useDesktopAuthStore } from "@/stores/auth";

const router = useRouter();
const authStore = useDesktopAuthStore();
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
    ElMessage.success(t("desktop.login.success"));
    router.push("/dashboard");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("desktop.login.failed"));
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login {
  display: grid;
  grid-template-columns: 1.2fr 400px;
  gap: 24px;
  min-height: 100vh;
  padding: 24px;
}

.login__panel,
.login__card {
  border: 1px solid var(--studio-border);
  border-radius: 30px;
  box-shadow: var(--studio-shadow);
}

.login__panel {
  padding: 36px;
  color: #f3f8ff;
  background:
    radial-gradient(circle at top left, rgba(125, 179, 255, 0.28), transparent 30%),
    linear-gradient(180deg, #18406f 0%, #0d2344 100%);
}

.login__eyebrow {
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.login__card {
  align-self: center;
  padding: 28px;
  background: rgba(255, 255, 255, 0.92);
}

.login__submit {
  width: 100%;
}

@media (max-width: 960px) {
  .login {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
