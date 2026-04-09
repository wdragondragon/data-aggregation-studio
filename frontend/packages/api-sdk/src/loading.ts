type LoadingListener = (loading: boolean, pendingCount: number) => void;

let pendingCount = 0;
const listeners = new Set<LoadingListener>();

function notifyListeners() {
  const loading = pendingCount > 0;
  for (const listener of listeners) {
    listener(loading, pendingCount);
  }
}

export function beginStudioApiRequest() {
  pendingCount += 1;
  notifyListeners();
}

export function endStudioApiRequest() {
  pendingCount = Math.max(0, pendingCount - 1);
  notifyListeners();
}

export function subscribeStudioApiLoading(listener: LoadingListener) {
  listeners.add(listener);
  listener(pendingCount > 0, pendingCount);
  return () => {
    listeners.delete(listener);
  };
}

export function isStudioApiLoading() {
  return pendingCount > 0;
}

export function getStudioApiPendingCount() {
  return pendingCount;
}
