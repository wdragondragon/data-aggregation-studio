import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { Buffer } from "node:buffer";
import { transform } from "esbuild";

const helperUrl = new URL("../src/components/models/modelFieldJsonImport.ts", import.meta.url);
const helperSource = await readFile(helperUrl, "utf8");
const transformed = await transform(helperSource, {
  format: "esm",
  loader: "ts",
  sourcemap: "inline",
  sourcefile: helperUrl.pathname,
  target: "node20",
});
const helper = await import(`data:text/javascript;base64,${Buffer.from(transformed.code).toString("base64")}`);

const {
  buildModelFieldImportTemplate,
  MODEL_FIELD_IMPORT_MAX_VISIBLE_ISSUES,
  MODEL_FIELD_TABLE_PAGE_SIZE,
  modelFieldPageCount,
  modelFieldRowsForPage,
  normalizeModelFieldPage,
  parseAndValidateModelFieldJson,
  visibleModelFieldImportIssues,
} = helper;

const fields = [
  { fieldKey: "name", fieldName: "Name", valueType: "STRING", required: true },
  { fieldKey: "type", fieldName: "Type", valueType: "STRING", required: true, options: ["STRING", "LONG"] },
  { fieldKey: "length", fieldName: "Length", valueType: "INTEGER", defaultValue: "255" },
  { fieldKey: "enabled", fieldName: "Enabled", valueType: "BOOLEAN", defaultValue: "false" },
  { fieldKey: "mode", fieldName: "Mode", valueType: "STRING", options: ["A", "B"], defaultValue: "A" },
  { fieldKey: "description", fieldName: "Description", valueType: "STRING" },
  { fieldKey: "tags", fieldName: "Tags", valueType: "ARRAY" },
  { fieldKey: "config", fieldName: "Config", valueType: "OBJECT" },
  { fieldKey: "sample", fieldName: "Sample", valueType: "JSON" },
  { fieldKey: "parentNode", fieldName: "Parent node", valueType: "STRING" },
];

function validate(value, schema = fields) {
  return parseAndValidateModelFieldJson(JSON.stringify(value), schema);
}

function issueCodes(result) {
  return result.issues.map((issue) => issue.code);
}

function assertIssue(result, code, path) {
  assert.equal(result.rows.length, 0);
  assert.ok(result.issues.some((issue) => issue.code === code && issue.path === path), `${code} at ${path}`);
}

{
  const result = validate([{ name: "id", type: "LONG" }]);
  assert.deepEqual(result.issues, []);
  assert.deepEqual(result.rows, [{ name: "id", type: "LONG", length: 255, enabled: false, mode: "A" }]);
}

{
  const result = validate({ columns: [{ name: "first", type: "STRING" }, { name: "second", type: "LONG" }] });
  assert.deepEqual(result.issues, []);
  assert.deepEqual(result.rows.map((row) => row.name), ["first", "second"]);
}

{
  const result = parseAndValidateModelFieldJson(`\ufeff${JSON.stringify([{ name: "bom", type: "STRING" }])}`, fields);
  assert.deepEqual(result.issues, []);
  assert.equal(result.rows[0].name, "bom");
}

{
  const result = validate([{ name: "nullable", type: "STRING", length: null, description: null }]);
  assert.deepEqual(result.issues, []);
  assert.equal(result.rows[0].length, null);
  assert.equal(result.rows[0].description, null);
}

{
  const template = buildModelFieldImportTemplate(fields);
  const result = parseAndValidateModelFieldJson(template, fields);
  assert.deepEqual(result.issues, []);
  assert.equal(result.rows.length, 1);
  assert.equal(result.rows[0].name, "example_field");
  assert.equal(result.rows[0].type, "STRING");
}

assertIssue(parseAndValidateModelFieldJson("{", fields), "INVALID_JSON", "$");
assertIssue(validate([]), "EMPTY_ROWS", "$");
assertIssue(validate("rows"), "INVALID_ROOT", "$");
assertIssue(validate({ columns: "rows" }), "INVALID_ROOT", "$.columns");
assertIssue(validate({ columns: [{ name: "id", type: "LONG" }], extra: true }), "UNEXPECTED_ROOT_KEY", "$.extra");
assertIssue(validate(["id"]), "INVALID_ROW", "$[0]");
assertIssue(validate([{ name: "id", type: "LONG", 中文列名: "编号" }]), "UNKNOWN_FIELD", "$[0].中文列名");
assertIssue(validate([{ type: "LONG" }]), "MISSING_REQUIRED", "$[0].name");
assertIssue(validate([{ name: "id" }]), "MISSING_REQUIRED", "$[0].type");
assertIssue(validate([{ name: "id", type: "LONG", length: "255" }]), "INVALID_TYPE", "$[0].length");
assertIssue(validate([{ name: "id", type: "LONG", enabled: "true" }]), "INVALID_TYPE", "$[0].enabled");
assertIssue(validate([{ name: "id", type: "DECIMAL" }]), "INVALID_OPTION", "$[0].type");

{
  const result = validate([
    { name: "CustomerId", type: "LONG" },
    { name: " CustomerId ", type: "STRING" },
    { name: "customerid", type: "STRING" },
  ]);
  assertIssue(result, "DUPLICATE_NAME", "$[1].name");
  const duplicate = result.issues.find((issue) => issue.code === "DUPLICATE_NAME");
  assert.equal(duplicate.firstPath, "$[0].name");
  assert.equal(duplicate.duplicateValue, "CustomerId");
}

{
  const rows = Array.from({ length: 300 }, (_, index) => ({
    name: `field_${String(index).padStart(3, "0")}`,
    type: index % 2 === 0 ? "STRING" : "LONG",
    length: index,
    enabled: index % 3 === 0,
  }));
  const result = validate(rows);
  assert.deepEqual(result.issues, []);
  assert.equal(result.rows.length, 300);
  assert.deepEqual(result.rows.map((row) => row.name), rows.map((row) => row.name));
  assert.equal(MODEL_FIELD_TABLE_PAGE_SIZE, 50);
  assert.equal(modelFieldPageCount(result.rows.length), 6);
  assert.equal(modelFieldRowsForPage(result.rows, 1).length, 50);
  assert.equal(modelFieldRowsForPage(result.rows, 6)[49].name, "field_299");
  assert.equal(normalizeModelFieldPage(7, result.rows.length), 6);

  const savePayload = { technicalMetadata: { columns: result.rows } };
  assert.equal(savePayload.technicalMetadata.columns.length, 300);
  assert.equal(savePayload.technicalMetadata.columns[173].name, "field_173");
}

{
  const invalidRows = Array.from({ length: 120 }, (_, index) => ({ unknown: index }));
  const result = validate(invalidRows);
  assert.ok(result.issues.length > 100);
  assert.equal(visibleModelFieldImportIssues(result.issues).length, MODEL_FIELD_IMPORT_MAX_VISIBLE_ISSUES);
  assert.ok(issueCodes(result).includes("UNKNOWN_FIELD"));
  assert.ok(issueCodes(result).includes("MISSING_REQUIRED"));
}

{
  const result = parseAndValidateModelFieldJson("[]", fields, 1);
  assertIssue(result, "TEXT_TOO_LARGE", "$");
}

{
  const utf8Source = JSON.stringify([{ name: "字段", type: "LONG" }]);
  const utf8Bytes = Buffer.byteLength(utf8Source, "utf8");
  assert.deepEqual(parseAndValidateModelFieldJson(utf8Source, fields, utf8Bytes).issues, []);
  assertIssue(parseAndValidateModelFieldJson(utf8Source, fields, utf8Bytes - 1), "TEXT_TOO_LARGE", "$");
}

console.log("model field JSON import tests passed");
