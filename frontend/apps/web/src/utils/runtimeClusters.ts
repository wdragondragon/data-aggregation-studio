import type { EntityId, RuntimeClusterView } from "@studio/api-sdk";

export interface RuntimeClusterSelectionState {
  unavailable: boolean;
  disabled: boolean;
}

export function sameEntityId(left?: EntityId | null, right?: EntityId | null) {
  return left != null && right != null && String(left) === String(right);
}

export function resolveRuntimeClusterSelectionState(
  clusterId: EntityId | null | undefined,
  selectedClusterIds: readonly EntityId[],
  authorizedClusterIds: ReadonlySet<string>,
): RuntimeClusterSelectionState {
  if (clusterId == null) {
    return { unavailable: true, disabled: true };
  }
  const normalizedId = String(clusterId);
  const unavailable = !authorizedClusterIds.has(normalizedId);
  const selected = selectedClusterIds.some((item) => String(item) === normalizedId);
  return {
    unavailable,
    disabled: unavailable && !selected,
  };
}

export function isRuntimeScheduleToggleDisabled(runtimeInvalid?: boolean, scheduleEnabled?: boolean) {
  return runtimeInvalid === true && scheduleEnabled !== true;
}

export function isRuntimeManualTriggerDisabled(shared?: boolean, runtimeInvalid?: boolean) {
  return shared === true || runtimeInvalid === true;
}

export function formatRuntimeClusterLabel(
  clusters: RuntimeClusterView[],
  clusterId?: EntityId | null,
  clusterCode?: string | null,
) {
  const cluster = clusters.find((item) => sameEntityId(item.id, clusterId));
  const name = cluster?.name?.trim();
  const code = cluster?.code?.trim() || clusterCode?.trim();
  if (name && code && name !== code) {
    return `${name} (${code})`;
  }
  if (name || code) {
    return name || code || "-";
  }
  return clusterId == null ? "-" : `#${clusterId}`;
}
