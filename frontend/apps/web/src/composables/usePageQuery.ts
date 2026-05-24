import { reactive } from "vue";

export interface PageQueryState {
  page: number;
  pageSize: number;
}

export function usePageQuery(defaultPageSize = 10) {
  const pagination = reactive<PageQueryState>({
    page: 1,
    pageSize: defaultPageSize,
  });

  function resetPage() {
    pagination.page = 1;
  }

  function setPage(page: number) {
    pagination.page = page;
  }

  function setPageSize(pageSize: number) {
    pagination.pageSize = pageSize;
    resetPage();
  }

  function ensureValidPage(total: number) {
    const maxPage = Math.max(1, Math.ceil(Number(total || 0) / pagination.pageSize));
    if (pagination.page > maxPage) {
      pagination.page = maxPage;
      return true;
    }
    return false;
  }

  return {
    pagination,
    resetPage,
    setPage,
    setPageSize,
    ensureValidPage,
  };
}
