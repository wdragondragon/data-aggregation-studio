import { ref } from "vue";

export function useTableSelection<T>() {
  const selectedRows = ref<T[]>([]);

  function handleSelectionChange(rows: T[]) {
    selectedRows.value = rows || [];
  }

  function clearSelection() {
    selectedRows.value = [];
  }

  return {
    selectedRows,
    handleSelectionChange,
    clearSelection,
  };
}
