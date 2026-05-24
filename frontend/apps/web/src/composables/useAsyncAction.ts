import { ref } from "vue";
import { ElMessage } from "element-plus";

type AsyncActionErrorMessage = string | ((error: unknown) => string);

export function resolveErrorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}

function resolveActionErrorMessage(error: unknown, message?: AsyncActionErrorMessage) {
  if (typeof message === "function") {
    return message(error);
  }
  return resolveErrorMessage(error, message || "操作失败");
}

export function useAsyncAction() {
  const loading = ref(false);

  async function run<T>(
    action: () => Promise<T>,
    options: {
      successMessage?: string;
      errorMessage?: AsyncActionErrorMessage;
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
        ElMessage.error(resolveActionErrorMessage(error, options.errorMessage));
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
