<template>
  <div class="login">
    <div class="login__hero">
      <p class="login__eyebrow">Web-first data orchestration</p>
      <h1>Build metadata-driven collection jobs without binding the platform to the engine reactor.</h1>
      <p>
        This studio runs beside DataAggregation, not inside it. Sign in to manage datasource schemas,
        physical models and DAG workflows from the browser.
      </p>
      <div class="login__tips">
        <span>Default account: <strong class="studio-mono">admin</strong></span>
        <span>Password: <strong class="studio-mono">admin123</strong></span>
      </div>
    </div>

    <div class="login__card">
      <h2>Sign In</h2>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="Username">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="Password">
          <el-input v-model="form.password" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-button type="primary" :loading="loading" class="login__submit" @click="submit">Enter Studio</el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useAuthStore } from "@/stores/auth";

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const loading = ref(false);
const form = reactive({
  username: "admin",
  password: "admin123",
});

async function submit() {
  loading.value = true;
  try {
    await authStore.login(form);
    ElMessage.success("Login succeeded");
    const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "/dashboard";
    router.push(redirect);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Login failed");
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
  color: #fff5eb;
  background:
    radial-gradient(circle at top left, rgba(247, 214, 146, 0.24), transparent 30%),
    linear-gradient(180deg, #6d3f2c 0%, #2d1c14 100%);
}

.login__hero h1 {
  margin: 0;
  font-size: clamp(34px, 5vw, 58px);
  line-height: 1.02;
}

.login__hero p {
  max-width: 720px;
  font-size: 17px;
  color: rgba(255, 245, 235, 0.8);
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
  background: rgba(255, 255, 255, 0.08);
}

.login__card {
  align-self: center;
  padding: 32px;
  background: rgba(255, 252, 245, 0.9);
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
