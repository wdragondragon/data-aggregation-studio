import type { FileTransferRunView } from "@studio/api-sdk";

export interface FileTransferRunListParams {
  pageNo: number;
  pageSize: number;
  triggerType: "MANUAL";
  statusGroup: "ACTIVE" | "TERMINAL";
}

interface FileTransferRunPage {
  items: FileTransferRunView[];
  total: number;
}

type FileTransferRunList = (params: FileTransferRunListParams) => Promise<FileTransferRunPage>;

const terminalStatuses = new Set(["SUCCESS", "PARTIAL_SUCCESS", "FAILED", "CANCELED"]);

export async function loadBoundedManualRuns(listRuns: FileTransferRunList) {
  const [firstActivePage, terminalPage] = await Promise.all([
    listRuns({ pageNo: 1, pageSize: 200, triggerType: "MANUAL", statusGroup: "ACTIVE" }),
    listRuns({ pageNo: 1, pageSize: 10, triggerType: "MANUAL", statusGroup: "TERMINAL" }),
  ]);
  const activeRuns = [...firstActivePage.items];
  let pageNo = 2;
  while (activeRuns.length < firstActivePage.total) {
    const page = await listRuns({ pageNo, pageSize: 200, triggerType: "MANUAL", statusGroup: "ACTIVE" });
    if (!page.items.length) break;
    activeRuns.push(...page.items);
    pageNo += 1;
  }
  return selectQueueRuns([...activeRuns, ...terminalPage.items]);
}

export function selectQueueRuns(runs: FileTransferRunView[]) {
  const uniqueRuns = new Map<string, FileTransferRunView>();
  runs.forEach((run, index) => {
    const key = run.id == null ? `missing-id-${index}` : String(run.id);
    uniqueRuns.set(key, run);
  });
  const sorted = [...uniqueRuns.values()].sort((leftRun, rightRun) =>
    String(rightRun.createdAt ?? "").localeCompare(String(leftRun.createdAt ?? "")));
  const active = sorted.filter((run) => !isTerminalRun(run));
  const completed = sorted.filter(isTerminalRun).slice(0, 10);
  return [...active, ...completed];
}

export async function mapWithConcurrency<T, R>(
  values: T[],
  concurrency: number,
  mapper: (value: T, index: number) => Promise<R>,
) {
  const results = new Array<R>(values.length);
  let nextIndex = 0;
  const workerCount = Math.min(values.length, Math.max(1, concurrency));
  await Promise.all(Array.from({ length: workerCount }, async () => {
    while (nextIndex < values.length) {
      const index = nextIndex;
      nextIndex += 1;
      results[index] = await mapper(values[index] as T, index);
    }
  }));
  return results;
}

function isTerminalRun(run: FileTransferRunView) {
  return terminalStatuses.has(String(run.status ?? "").toUpperCase());
}
