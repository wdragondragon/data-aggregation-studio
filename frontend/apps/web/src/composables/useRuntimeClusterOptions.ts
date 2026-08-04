import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import type { EntityId, RuntimeClusterView } from "@studio/api-sdk";
import { studioApi } from "@/api/studio";

export function useRuntimeClusterOptions() {
  const { t } = useI18n();
  const runtimeClusters = ref<RuntimeClusterView[]>([]);
  const runtimeClustersLoading = ref(false);
  let loadSequence = 0;

  const singleRuntimeCluster = computed(() => (
    runtimeClusters.value.length === 1 && runtimeClusters.value[0]?.enabled !== false
  ));

  async function loadRuntimeClusters() {
    const sequence = ++loadSequence;
    runtimeClustersLoading.value = true;
    try {
      const options = await studioApi.runtimeClusters.options();
      if (sequence === loadSequence) {
        runtimeClusters.value = options;
      }
      return sequence === loadSequence ? options : runtimeClusters.value;
    } catch (error) {
      if (sequence !== loadSequence) {
        return runtimeClusters.value;
      }
      throw error;
    } finally {
      if (sequence === loadSequence) {
        runtimeClustersLoading.value = false;
      }
    }
  }

  function resolveInitialRuntimeClusterId(current?: EntityId | null): EntityId | undefined {
    const selectableClusters = runtimeClusters.value.filter((item) => item.enabled !== false);
    if (current != null && selectableClusters.some((item) => String(item.id) === String(current))) {
      return current;
    }
    return selectableClusters.length === 1 ? selectableClusters[0]?.id : undefined;
  }

  function ensureRuntimeClusterOption(
    clusterId?: EntityId | null,
    clusterName?: string | null,
    clusterCode?: string | null,
  ) {
    if (clusterId == null || runtimeClusters.value.some((item) => String(item.id) === String(clusterId))) {
      return;
    }
    runtimeClusters.value = [
      ...runtimeClusters.value,
      {
        id: clusterId,
        name: clusterName?.trim() || `#${clusterId}`,
        code: clusterCode?.trim() || undefined,
        enabled: false,
        status: "UNAVAILABLE",
      },
    ];
  }

  function runtimeClusterOptionLabel(cluster: RuntimeClusterView) {
    const name = cluster.name?.trim();
    const code = cluster.code?.trim();
    const label = name && code && name !== code
      ? `${name} (${code})`
      : name || code || `#${cluster.id ?? "-"}`;
    return cluster.enabled === false
      ? `${label} - ${t("web.runtimeClusterSelection.unavailableOption")}`
      : label;
  }

  function runtimeClusterOptionDisabled(cluster: RuntimeClusterView) {
    return cluster.enabled === false;
  }

  return {
    runtimeClusters,
    runtimeClustersLoading,
    singleRuntimeCluster,
    loadRuntimeClusters,
    resolveInitialRuntimeClusterId,
    ensureRuntimeClusterOption,
    runtimeClusterOptionLabel,
    runtimeClusterOptionDisabled,
  };
}
