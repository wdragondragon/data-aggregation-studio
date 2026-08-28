import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { Buffer } from "node:buffer";
import { transform } from "esbuild";

const supportUrl = new URL("../src/components/collection-task/collectionTaskEditorSupport.ts", import.meta.url);
const supportSource = await readFile(supportUrl, "utf8");
const transformed = await transform(supportSource, {
  format: "esm",
  loader: "ts",
  sourcemap: "inline",
  sourcefile: supportUrl.pathname,
  target: "node20",
});
const support = await import(`data:text/javascript;base64,${Buffer.from(transformed.code).toString("base64")}`);
const { isStreamingTaskConfigurationLocked } = support;

assert.equal(isStreamingTaskConfigurationLocked("BATCH", "RUNNING", "RUNNING"), false);
assert.equal(isStreamingTaskConfigurationLocked("STREAMING", "RUNNING", "STARTING"), true);
assert.equal(isStreamingTaskConfigurationLocked("STREAMING", "RUNNING", "RUNNING"), true);
assert.equal(isStreamingTaskConfigurationLocked("STREAMING", "RUNNING", "RECOVERING"), true);
assert.equal(isStreamingTaskConfigurationLocked("STREAMING", "RUNNING", "FAILED"), true);
assert.equal(isStreamingTaskConfigurationLocked("STREAMING", "STOPPED", "STOPPING"), true);
assert.equal(isStreamingTaskConfigurationLocked("STREAMING", "STOPPED", "STOPPED"), false);
assert.equal(isStreamingTaskConfigurationLocked("STREAMING", undefined, undefined), false);

const editorUrl = new URL("../src/views/CollectionTaskEditorView.vue", import.meta.url);
const headerUrl = new URL("../src/components/collection-task/CollectionTaskEditorHeader.vue", import.meta.url);
const footerUrl = new URL("../src/components/collection-task/CollectionTaskEditorFooter.vue", import.meta.url);
const bindingUrl = new URL("../src/components/collection-task/CollectionTaskBindingSection.vue", import.meta.url);
const mappingEditorUrl = new URL("../src/components/CollectionTaskFieldMappingEditor.vue", import.meta.url);
const [editor, header, footer, binding, mappingEditor] = await Promise.all(
  [editorUrl, headerUrl, footerUrl, bindingUrl, mappingEditorUrl].map((url) => readFile(url, "utf8")),
);

assert.match(editor, /<el-form :disabled="streamingLocked"/);
assert.match(editor, /:locked="streamingLocked"/);
assert.match(editor, /if \(streamingLocked\.value\)[\s\S]*streamingEditLocked/);
assert.match(header, /:disabled="locked"/);
assert.match(footer, /:disabled="locked"/);
assert.ok((binding.match(/:disabled="(?:streamingLocked|streamingLocked \|\|)/g) ?? []).length >= 14);
assert.ok((mappingEditor.match(/:disabled="disabled"/g) ?? []).length >= 6);

console.log("collection task streaming lock tests passed");
