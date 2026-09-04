import type { EntityId, ManagedFileView, PageResult } from "@studio/api-sdk";

export interface ManagedFileClient {
  upload(file: File, policyCode: string, onProgress?: (percentage: number) => void): Promise<ManagedFileView>;
  queryPage(params?: { pageNum?: number; pageSize?: number; policyCode?: string; status?: string }): Promise<PageResult<ManagedFileView>>;
  get(id: EntityId): Promise<ManagedFileView>;
  download(id: EntityId): Promise<Blob>;
  delete(id: EntityId): Promise<void>;
}
