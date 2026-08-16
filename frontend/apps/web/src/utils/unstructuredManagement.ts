export interface UnstructuredDeleteEntry {
  directory?: boolean;
  name: string;
  path: string;
}

export interface UnstructuredDeleteRequest {
  recursiveConfirmed: boolean;
  sourcePath: string;
}

export interface UnstructuredDeleteFailure<T> {
  entry: T;
  error: unknown;
}

export interface UnstructuredDeleteBatchResult<T> {
  failed: Array<UnstructuredDeleteFailure<T>>;
  succeeded: T[];
}

export async function deleteUnstructuredEntries<T extends UnstructuredDeleteEntry>(
  entries: readonly T[],
  deleteEntry: (request: UnstructuredDeleteRequest, entry: T) => Promise<unknown>,
): Promise<UnstructuredDeleteBatchResult<T>> {
  const result: UnstructuredDeleteBatchResult<T> = { failed: [], succeeded: [] };

  // Keep remote file operations ordered; FTP/SFTP implementations may share a session per request scope.
  for (const entry of [...entries]) {
    try {
      await deleteEntry({
        sourcePath: entry.path,
        recursiveConfirmed: Boolean(entry.directory),
      }, entry);
      result.succeeded.push(entry);
    } catch (error) {
      result.failed.push({ entry, error });
    }
  }

  return result;
}
