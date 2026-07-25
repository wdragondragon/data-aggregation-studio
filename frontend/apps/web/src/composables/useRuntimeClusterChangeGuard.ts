import { ref } from "vue";
import { ElMessageBox } from "element-plus";
import { useI18n } from "vue-i18n";
import type { EntityId } from "@studio/api-sdk";

function sameCluster(left?: EntityId | null, right?: EntityId | null) {
  return String(left ?? "") === String(right ?? "");
}

function hasCluster(value?: EntityId | null) {
  return value != null && String(value) !== "";
}

/**
 * Keeps the last accepted cluster so a cancelled selection never clears the
 * datasource/model state that belongs to the current cluster.
 */
export function useRuntimeClusterChangeGuard() {
  const { t } = useI18n();
  const acceptedRuntimeClusterId = ref<EntityId | undefined>();
  let confirmationSequence = 0;

  function acceptRuntimeCluster(value?: EntityId | null) {
    acceptedRuntimeClusterId.value = hasCluster(value) ? value ?? undefined : undefined;
  }

  async function confirmRuntimeClusterChange(next?: EntityId | null) {
    const sequence = ++confirmationSequence;
    const previous = acceptedRuntimeClusterId.value;
    if (sameCluster(previous, next)) {
      return false;
    }
    if (!hasCluster(previous)) {
      acceptRuntimeCluster(next);
      return true;
    }
    try {
      await ElMessageBox.confirm(
        t("web.runtimeClusterSelection.changeConfirmMessage"),
        t("web.runtimeClusterSelection.changeConfirmTitle"),
        {
          type: "warning",
          confirmButtonText: t("common.confirm"),
          cancelButtonText: t("common.cancel"),
        },
      );
      if (sequence !== confirmationSequence) {
        return false;
      }
      acceptRuntimeCluster(next);
      return true;
    } catch {
      return false;
    }
  }

  return {
    acceptedRuntimeClusterId,
    acceptRuntimeCluster,
    confirmRuntimeClusterChange,
  };
}
