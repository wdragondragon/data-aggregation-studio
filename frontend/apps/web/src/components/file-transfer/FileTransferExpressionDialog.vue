<template>
  <el-dialog
    v-model="dialogVisible"
    :title="kind === 'function' ? '插入函数' : '插入模板变量'"
    width="min(900px, calc(100vw - 32px))"
    destroy-on-close
    append-to-body
  >
    <template v-if="kind === 'function'">
      <div class="expression-layout">
        <div class="expression-list">
          <button
            v-for="item in dynamicFunctionCatalog"
            :key="item.name"
            type="button"
            :class="['expression-item', { active: selectedName === item.name }]"
            @click="selectedName = item.name"
          >
            <strong>{{ item.label }}</strong>
            <span>{{ item.summary }}</span>
          </button>
        </div>
        <div class="expression-content">
          <div class="expression-card">
            <strong>{{ selectedFunction.label }}</strong>
            <code>{{ selectedFunction.signature }}</code>
            <p>{{ selectedFunction.description }}</p>
            <small>示例：{{ selectedFunction.example }}</small>
          </div>
          <p v-if="selectedSnippet" class="selection-hint">当前选中内容：<code>{{ selectedSnippet }}</code></p>
          <div class="argument-grid">
            <el-form-item v-for="param in selectedFunction.params" :key="param.key" :label="param.label">
              <el-input v-model="args[param.key]" :placeholder="param.placeholder" />
              <small>{{ param.description }}<span v-if="param.required === false">（可留空）</span></small>
            </el-form-item>
          </div>
          <div class="expression-preview"><span>表达式预览</span><pre>{{ functionExpression }}</pre></div>
        </div>
      </div>
    </template>
    <template v-else>
      <div class="template-grid">
        <button
          v-for="item in templateOptions"
          :key="item.value"
          type="button"
          class="template-item"
          @click="confirm(item.value)"
        >
          <code>{{ item.value }}</code>
          <span>{{ item.label }}</span>
        </button>
      </div>
      <p class="dialog-hint">先函数、后模板。模板会在运行时解析，源端不支持相对路径和文件名变量。</p>
    </template>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button v-if="kind === 'function'" type="primary" @click="confirm(functionExpression)">插入</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { dynamicFunctionCatalog, type DynamicFunctionParamSchema } from "@studio/meta-form";

const props = withDefaults(defineProps<{
  visible: boolean;
  kind: "function" | "template";
  selectedSnippet?: string;
  allowPerFileTemplates?: boolean;
}>(), { selectedSnippet: "", allowPerFileTemplates: false });

const emit = defineEmits<{
  "update:visible": [value: boolean];
  confirm: [expression: string];
}>();

const dialogVisible = computed({ get: () => props.visible, set: value => emit("update:visible", value) });
const selectedName = ref("getCurrentTime");
const args = reactive<Record<string, string>>({});
const selectedFunction = computed(() => dynamicFunctionCatalog.find(item => item.name === selectedName.value) ?? dynamicFunctionCatalog[0]);
const selectedSnippet = computed(() => props.selectedSnippet || "");
const templateOptions = computed(() => [
  { value: "${runId}", label: "任务运行 ID" },
  { value: "${plannedAt}", label: "计划开始时间" },
  { value: "${now:yyyyMMdd}", label: "计划时间格式化" },
  { value: "${date:yyyyMMdd}", label: "日期格式化" },
  { value: "${param:key}", label: "运行参数（param）" },
  { value: "${parameter:key}", label: "运行参数（parameter）" },
  { value: "${parameter.key}", label: "运行参数（点号）" },
  ...(props.allowPerFileTemplates ? [
    { value: "${relativePath}", label: "源文件相对路径" },
    { value: "${fileName}", label: "源文件名" },
  ] : []),
]);
const functionExpression = computed(() => {
  const values = selectedFunction.value.params.map((param: DynamicFunctionParamSchema) => formatArg(param, args[param.key])).filter(value => value !== null);
  return `$${selectedFunction.value.name}(${values.join(", ")})`;
});

watch(() => props.visible, visible => { if (visible) resetArgs(); });
watch(selectedName, () => { if (props.visible) resetArgs(); });

function resetArgs() {
  Object.keys(args).forEach(key => delete args[key]);
  selectedFunction.value.params.forEach(param => {
    args[param.key] = param.useSelectedSnippet && selectedSnippet.value ? selectedSnippet.value : param.defaultValue || "";
  });
}

function formatArg(param: DynamicFunctionParamSchema, raw: string | undefined) {
  const value = (raw || "").trim();
  if (!value) return param.required === false ? null : "";
  if (param.autoQuote === false || value.startsWith("'") || value.startsWith('"') || value.startsWith("$") || value.startsWith("${")) return value;
  return `'${value}'`;
}

function confirm(expression: string) {
  if (!expression.trim()) return;
  const missing = selectedFunction.value.params.find(param => param.required !== false && !(args[param.key] || "").trim());
  if (props.kind === "function" && missing) {
    ElMessage.warning(`请先填写${missing.label}`);
    return;
  }
  emit("confirm", expression);
}
</script>

<style scoped>
.expression-layout { display: grid; grid-template-columns: 240px minmax(0, 1fr); gap: 16px; }
.expression-list { display: grid; gap: 6px; max-height: 52vh; overflow: auto; }
.expression-item, .template-item { border: 1px solid var(--studio-border); border-radius: 8px; background: var(--studio-surface-strong); padding: 9px 11px; text-align: left; cursor: pointer; }
.expression-item.active, .expression-item:hover, .template-item:hover { border-color: var(--studio-primary); }
.expression-item strong, .expression-item span { display: block; }
.expression-item span, .template-item span, .expression-card p, .expression-card small, .argument-grid small, .dialog-hint { color: var(--studio-text-soft); font-size: 12px; line-height: 1.5; }
.expression-content { display: grid; gap: 12px; min-width: 0; }
.expression-card { display: grid; gap: 6px; padding: 13px; border: 1px solid var(--studio-border); border-radius: 8px; }
.expression-card code, .selection-hint code, .template-item code { color: var(--studio-primary); overflow-wrap: anywhere; }
.expression-card p { margin: 0; }
.selection-hint { margin: 0; padding: 9px 11px; background: rgba(15, 23, 42, .04); }
.argument-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 14px; }
.argument-grid :deep(.el-form-item) { margin-bottom: 0; }
.expression-preview { border: 1px dashed var(--studio-border); border-radius: 8px; padding: 10px; }
.expression-preview span { color: var(--studio-text-soft); font-size: 12px; }
.expression-preview pre { margin: 8px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.template-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.template-item { display: grid; gap: 6px; }
.dialog-hint { margin: 14px 0 0; }
@media (max-width: 720px) { .expression-layout, .argument-grid, .template-grid { grid-template-columns: 1fr; } }
</style>
