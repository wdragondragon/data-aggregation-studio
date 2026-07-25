import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { transform } from "esbuild";

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const source = await readFile(resolve(appRoot, "src/utils/runtimeClusters.ts"), "utf8");
const apiClientSource = await readFile(resolve(appRoot, "../../packages/api-sdk/src/client.ts"), "utf8");
const compiled = await transform(source, { loader: "ts", format: "esm", target: "es2020" });
const moduleUrl = `data:text/javascript;base64,${Buffer.from(compiled.code).toString("base64")}`;
const { isRuntimeManualTriggerDisabled, isRuntimeScheduleToggleDisabled, resolveRuntimeClusterSelectionState } = await import(moduleUrl);

function functionBody(sourceText, functionName) {
  const signature = `function ${functionName}`;
  const signatureIndex = sourceText.indexOf(signature);
  assert.notEqual(signatureIndex, -1, `${functionName} must exist.`);
  const bodyStart = sourceText.indexOf("{", signatureIndex);
  assert.notEqual(bodyStart, -1, `${functionName} must have a body.`);
  let depth = 0;
  for (let index = bodyStart; index < sourceText.length; index += 1) {
    if (sourceText[index] === "{") depth += 1;
    if (sourceText[index] === "}") depth -= 1;
    if (depth === 0) return sourceText.slice(bodyStart + 1, index);
  }
  assert.fail(`${functionName} body is not balanced.`);
}

async function assertClearedBeforeReload(fileName, clearStatements, reloadStatement) {
  const editorSource = await readFile(resolve(appRoot, "src/views", fileName), "utf8");
  const body = functionBody(editorSource, "handleRuntimeClusterChange");
  const reloadIndex = body.indexOf(reloadStatement);
  assert.notEqual(reloadIndex, -1, `${fileName} must reload cluster-scoped options.`);
  for (const statement of clearStatements) {
    const clearIndex = body.indexOf(statement);
    assert.notEqual(clearIndex, -1, `${fileName} must clear ${statement} when the runtime cluster changes.`);
    assert.ok(clearIndex < reloadIndex, `${fileName} must clear ${statement} before starting the asynchronous reload.`);
  }
}

const authorized = new Set(["1"]);

assert.deepEqual(
  resolveRuntimeClusterSelectionState(1, [], authorized),
  { unavailable: false, disabled: false },
  "Authorized clusters must remain selectable.",
);
assert.deepEqual(
  resolveRuntimeClusterSelectionState(2, [2], authorized),
  { unavailable: true, disabled: false },
  "An unavailable selected cluster must remain removable.",
);
assert.deepEqual(
  resolveRuntimeClusterSelectionState(2, [], authorized),
  { unavailable: true, disabled: true },
  "An unavailable cluster must not be selectable again after removal.",
);

assert.equal(
  isRuntimeScheduleToggleDisabled(true, false),
  true,
  "An invalid resource must not enable a disabled schedule.",
);
assert.equal(
  isRuntimeScheduleToggleDisabled(true, true),
  false,
  "An invalid resource with an active schedule must still be able to disable it.",
);
assert.equal(
  isRuntimeScheduleToggleDisabled(false, false),
  false,
  "A valid resource must be able to enable its schedule.",
);

assert.equal(
  isRuntimeManualTriggerDisabled(true, false),
  true,
  "A shared resource must not expose a manual trigger that the backend rejects.",
);
assert.equal(
  isRuntimeManualTriggerDisabled(false, true),
  true,
  "An invalid runtime binding must block manual triggering.",
);
assert.equal(
  isRuntimeManualTriggerDisabled(false, false),
  false,
  "An owned resource with a valid runtime binding must remain triggerable.",
);

await Promise.all([
  assertClearedBeforeReload("CollectionTaskEditorView.vue", ["datasources.value = [];"], "await loadDatasourcesForRuntimeCluster();"),
  assertClearedBeforeReload("QualityTaskEditorView.vue", ["datasources.value = [];"], "await loadDatasources();"),
  assertClearedBeforeReload("WorkflowEditorView.vue", ["datasources.value = [];", "form.nodes = form.nodes.map"], "await loadDatasourcesForRuntimeCluster();"),
  assertClearedBeforeReload("DataDevelopmentView.vue", ["sqlDatasources.value = [];", "datasourceOptions.value = [];"], "await ensureEditorReferenceData"),
  assertClearedBeforeReload("DataServiceEditorView.vue", ["datasources.value = [];"], "await loadDatasourcesForRuntimeCluster();"),
  assertClearedBeforeReload("DataIngestionServiceEditorView.vue", ["datasources.value = [];"], "await loadDatasourcesForRuntimeCluster();"),
  assertClearedBeforeReload("ProtocolConversionServiceEditorView.vue", ["datasources.value = [];"], "await loadDatasourcesForRuntimeCluster();"),
]);

assert.match(
  apiClientSource,
  /listSqlDatasources\(runtimeClusterId: EntityId\)[\s\S]{0,300}params: \{ runtimeClusterId \}/,
  "The full SQL datasource compatibility API must send the selected runtime cluster.",
);
assert.match(
  apiClientSource,
  /listSqlDatasourceOptions\(runtimeClusterId: EntityId\)[\s\S]{0,300}params: \{ runtimeClusterId \}/,
  "SQL datasource options must send the selected runtime cluster.",
);
const qualityRuleEditorSource = await readFile(resolve(appRoot, "src/views/QualityRuleEditorView.vue"), "utf8");
assert.doesNotMatch(
  qualityRuleEditorSource,
  /listSqlDatasourceOptions\(/,
  "Quality rule type metadata must not call a cluster-scoped datasource option API without a cluster selection.",
);

console.log("runtime cluster selection regression passed");
