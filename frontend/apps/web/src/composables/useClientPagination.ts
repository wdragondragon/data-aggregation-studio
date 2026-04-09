import { computed, reactive, watch, type Ref } from "vue";

export interface ClientPaginationState {
  page: number;
  pageSize: number;
}

export function getPaginatedRowNumber(pagination: ClientPaginationState, index: number) {
  return (pagination.page - 1) * pagination.pageSize + index + 1;
}

export function useClientPagination<T>(items: Ref<T[]>, defaultPageSize = 10) {
  const pagination = reactive<ClientPaginationState>({
    page: 1,
    pageSize: defaultPageSize,
  });

  const pagedItems = computed(() => {
    const start = (pagination.page - 1) * pagination.pageSize;
    return items.value.slice(start, start + pagination.pageSize);
  });

  function resetPagination() {
    pagination.page = 1;
  }

  watch(
    () => items.value.length,
    (length) => {
      const maxPage = Math.max(1, Math.ceil(length / pagination.pageSize));
      if (pagination.page > maxPage) {
        pagination.page = maxPage;
      }
    },
    { immediate: true },
  );

  watch(
    () => pagination.pageSize,
    () => {
      const maxPage = Math.max(1, Math.ceil(items.value.length / pagination.pageSize));
      if (pagination.page > maxPage) {
        pagination.page = maxPage;
      }
    },
  );

  return {
    pagination,
    pagedItems,
    resetPagination,
  };
}
