<template>
  <div class="register-page">
    <section class="register-card">
      <div class="register-card__header">
        <p class="register-card__eyebrow">{{ t("web.register.eyebrow") }}</p>
        <h2>{{ t("web.register.title") }}</h2>
        <p>{{ t("web.register.description") }}</p>
      </div>

      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item :label="t('web.register.usernameLabel')">
          <el-input v-model="form.username" :placeholder="t('web.register.usernamePlaceholder')" autocomplete="username" />
        </el-form-item>
        <el-form-item :label="t('web.register.passwordLabel')">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="t('web.register.passwordPlaceholder')"
            autocomplete="new-password"
          />
        </el-form-item>
        <el-form-item :label="t('web.register.displayNameLabel')">
          <el-input v-model="form.displayName" :placeholder="t('web.register.displayNamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('web.register.reasonLabel')">
          <el-input
            v-model="form.reason"
            type="textarea"
            :rows="4"
            resize="none"
            :placeholder="t('web.register.reasonPlaceholder')"
          />
        </el-form-item>
        <div class="register-card__actions">
          <el-button @click="router.push('/login')">{{ t("common.cancel") }}</el-button>
          <el-button type="primary" native-type="submit" :loading="loading">{{ t("web.register.submit") }}</el-button>
        </div>
      </el-form>
    </section>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useI18n } from "vue-i18n";
import { studioApi } from "@/api/studio";

const { t } = useI18n();
const router = useRouter();
const loading = ref(false);
const form = reactive({
  username: "",
  password: "",
  displayName: "",
  reason: "",
});

async function submit() {
  loading.value = true;
  try {
    await studioApi.auth.submitRegisterRequest({
      username: form.username.trim(),
      password: form.password,
      displayName: form.displayName.trim() || undefined,
      reason: form.reason.trim() || undefined,
    });
    ElMessage.success(t("web.register.submitSuccess"));
    router.push("/login");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t("web.register.submitFailed"));
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.register-page {
  display: grid;
  place-items: center;
  min-height: 100vh;
  padding: 24px;
  background:
    radial-gradient(circle at top right, rgba(56, 189, 248, 0.12), transparent 22%),
    linear-gradient(180deg, #f7fbff 0%, #ecf5ff 100%);
}

.register-card {
  width: min(680px, 100%);
  padding: 32px;
  border: 1px solid rgba(16, 78, 139, 0.12);
  border-radius: 28px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 20px 48px rgba(16, 78, 139, 0.12);
}

.register-card__header {
  display: grid;
  gap: 10px;
  margin-bottom: 20px;
}

.register-card__eyebrow {
  margin: 0;
  color: var(--studio-primary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.register-card__header h2 {
  margin: 0;
}

.register-card__header p {
  margin: 0;
  color: var(--studio-text-soft);
  line-height: 1.6;
}

.register-card__actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>
