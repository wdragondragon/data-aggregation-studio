import assert from "node:assert/strict";
import { Buffer } from "node:buffer";
import { readFile } from "node:fs/promises";
import { transform } from "esbuild";

const helperUrl = new URL("../src/jsonEditorValue.ts", import.meta.url);
const helperSource = await readFile(helperUrl, "utf8");
const transformed = await transform(helperSource, {
  format: "esm",
  loader: "ts",
  sourcemap: "inline",
  sourcefile: helperUrl.pathname,
  target: "node20",
});
const helper = await import(`data:text/javascript;base64,${Buffer.from(transformed.code).toString("base64")}`);
const { formatJsonEditorValue } = helper;

assert.equal(formatJsonEditorValue(undefined), "");
assert.equal(formatJsonEditorValue(null), "");
assert.equal(formatJsonEditorValue("{}"), "{}");
assert.equal(formatJsonEditorValue('{"session.timeout.ms":"30000"}'), '{"session.timeout.ms":"30000"}');
assert.equal(formatJsonEditorValue({}), "{}");
assert.equal(
  formatJsonEditorValue({ "session.timeout.ms": "30000", nested: { enabled: true } }),
  '{\n  "session.timeout.ms": "30000",\n  "nested": {\n    "enabled": true\n  }\n}',
);
assert.equal(formatJsonEditorValue(["earliest", "latest"]), '[\n  "earliest",\n  "latest"\n]');
assert.equal(formatJsonEditorValue(true), "true");
assert.equal(formatJsonEditorValue(500), "500");

const circular = {};
circular.self = circular;
assert.equal(formatJsonEditorValue(circular), "");

const componentUrl = new URL("../src/MetaFormRenderer.vue", import.meta.url);
const componentSource = await readFile(componentUrl, "utf8");
assert.match(componentSource, /:model-value="componentFieldValue\(field\)"/);
assert.match(componentSource, /field\.componentType === "JSON_EDITOR" \? formatJsonEditorValue\(value\) : value/);
assert.match(componentSource, /field\?\.componentType === "JSON_EDITOR"[\s\S]*formatJsonEditorValue\(value\)/);

console.log("meta form JSON editor value tests passed");
