import { ref } from "vue";

export function useDialogForm<T>(createInitialValue: () => T) {
  const visible = ref(false);
  const form = ref<T>(createInitialValue());

  function open(value?: T) {
    form.value = value == null ? createInitialValue() : value;
    visible.value = true;
  }

  function close() {
    visible.value = false;
  }

  function reset() {
    form.value = createInitialValue();
  }

  return {
    visible,
    form,
    open,
    close,
    reset,
  };
}
