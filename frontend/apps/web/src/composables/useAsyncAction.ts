import { ref } from "vue";
import { ElMessage } from "element-plus";

export function resolveErrorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

export function useAsyncAction() {
  const loading = ref(false);

  async function run<T>(
    action: () => Promise<T>,
    options: {
      successMessage?: string;
      errorMessage?: string;
      ignoreCancel?: boolean;
    } = {},
  ) {
    loading.value = true;
    try {
      const result = await action();
      if (options.successMessage) {
        ElMessage.success(options.successMessage);
      }
      return result;
    } catch (error) {
      if (!(options.ignoreCancel && error === "cancel")) {
        ElMessage.error(resolveErrorMessage(error, options.errorMessage || "操作失败"));
      }
      throw error;
    } finally {
      loading.value = false;
    }
  }

  return {
    loading,
    run,
  };
}
