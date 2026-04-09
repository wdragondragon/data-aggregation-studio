<template>
  <div class="cron-picker">
    <el-input
      :model-value="innerValue"
      :readonly="readonly"
      :disabled="disabled"
      class="cron-picker__input"
      @update:model-value="handleManualInput"
    >
      <template #prepend>
        <span class="cron-picker__label">{{ label }}</span>
      </template>
      <template #append>
        <el-popover
          v-model:visible="popoverVisible"
          placement="bottom-end"
          trigger="click"
          :width="820"
          popper-class="cron-picker__popover"
        >
          <template #reference>
            <el-button :disabled="disabled || readonly">
              {{ pickerTriggerLabel }}
            </el-button>
          </template>

          <div class="cron-picker__popover-body">
            <NoVue3Cron
              :cron-value="safeCronValue"
              :i18n="cronLocale"
              max-height="360px"
              @change="handleCronChange"
              @close="popoverVisible = false"
            />
          </div>
        </el-popover>
      </template>
    </el-input>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { noVue3Cron as NoVue3Cron } from "no-vue3-cron";
import "no-vue3-cron/lib/noVue3Cron.css";

interface Props {
  modelValue?: string;
  disabled?: boolean;
  readonly?: boolean;
  label?: string;
}

const DEFAULT_CRON = "0 */30 * * * ? *";

const props = withDefaults(defineProps<Props>(), {
  modelValue: DEFAULT_CRON,
  disabled: false,
  readonly: false,
  label: "Cron",
});

const emit = defineEmits<{
  (event: "update:modelValue", value: string): void;
}>();

const { locale } = useI18n();
const innerValue = ref(normalizeCron(props.modelValue));
const popoverVisible = ref(false);
const cronLocale = computed(() => (locale.value.startsWith("zh") ? "cn" : "en"));
const pickerTriggerLabel = computed(() => (cronLocale.value === "cn" ? "设置" : "Set"));
const safeCronValue = computed(() => normalizeCron(innerValue.value));

watch(
  () => props.modelValue,
  (value) => {
    const normalized = normalizeCron(value);
    if (normalized !== innerValue.value) {
      innerValue.value = normalized;
    }
  },
);

watch(innerValue, (value) => {
  emit("update:modelValue", normalizeCron(value));
});

function handleManualInput(value: string) {
  innerValue.value = typeof value === "string" ? value : DEFAULT_CRON;
}

function handleCronChange(value: string) {
  if (typeof value === "string") {
    innerValue.value = normalizeCron(value);
  }
}

function normalizeCron(value?: string | null) {
  if (typeof value !== "string") {
    return DEFAULT_CRON;
  }

  const trimmed = value.trim();
  if (!trimmed) {
    return DEFAULT_CRON;
  }

  const parts = trimmed.split(/\s+/).filter(Boolean);
  if (parts.length < 6) {
    return DEFAULT_CRON;
  }

  if (parts.length === 6) {
    return `${parts.join(" ")} *`;
  }

  return parts.slice(0, 7).join(" ");
}
</script>

<style scoped>
.cron-picker {
  width: 100%;
}

.cron-picker__input {
  width: 100%;
}

.cron-picker__label {
  min-width: 92px;
  font-size: 12px;
}

.cron-picker__popover-body {
  width: min(100%, 780px);
  max-width: 100%;
  overflow: hidden;
}

:deep(.no-vue3-cron-div) {
  width: 100%;
}

:deep(.no-vue3-cron-div .language) {
  display: none;
}

:deep(.no-vue3-cron-div .tabBody) {
  width: 100%;
  overflow-x: auto;
}

:deep(.no-vue3-cron-div .el-tabs__item) {
  padding: 0 14px;
}

:deep(.no-vue3-cron-div .el-radio-group) {
  display: grid;
  gap: 10px;
}

:deep(.no-vue3-cron-div .el-checkbox-group) {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(64px, 1fr));
  gap: 10px 14px;
}

:deep(.no-vue3-cron-div .el-checkbox) {
  margin-right: 0;
}

:deep(.no-vue3-cron-div .bottom) {
  justify-content: flex-end;
  gap: 12px;
  padding-top: 8px;
}

:deep(.no-vue3-cron-div .bottom .value) {
  margin-right: auto;
  color: var(--studio-text-muted);
}

@media (max-width: 900px) {
  :deep(.no-vue3-cron-div .el-checkbox-group) {
    grid-template-columns: repeat(auto-fit, minmax(52px, 1fr));
  }
}
</style>
