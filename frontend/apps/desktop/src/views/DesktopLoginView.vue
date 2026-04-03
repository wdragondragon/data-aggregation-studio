<template>
  <div class="login">
    <div class="login__panel">
      <p class="login__eyebrow">Offline desktop console</p>
      <h1>Run local metadata and ETL workflows without reaching the server side.</h1>
      <p>The desktop shell talks to the local runtime and still keeps import/export as the exchange boundary.</p>
    </div>

    <div class="login__card">
      <h2>Desktop Sign In</h2>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="Username">
          <el-input v-model="form.username" />
        </el-form-item>
        <el-form-item label="Password">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-button type="primary" class="login__submit" :loading="loading" @click="submit">Open Runtime</el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useDesktopAuthStore } from "@/stores/auth";

const router = useRouter();
const authStore = useDesktopAuthStore();
const loading = ref(false);
const form = reactive({
  username: "admin",
  password: "admin123",
});

async function submit() {
  loading.value = true;
  try {
    await authStore.login(form);
    ElMessage.success("Desktop runtime unlocked");
    router.push("/home");
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
  color: #fff7ef;
  background:
    radial-gradient(circle at top left, rgba(247, 214, 146, 0.24), transparent 30%),
    linear-gradient(180deg, #2e4f63 0%, #1d2530 100%);
}

.login__eyebrow {
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.login__card {
  align-self: center;
  padding: 28px;
  background: rgba(255, 252, 245, 0.9);
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
