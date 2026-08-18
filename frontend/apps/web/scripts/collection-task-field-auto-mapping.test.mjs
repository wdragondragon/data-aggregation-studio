import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { Buffer } from "node:buffer";
import { transform } from "esbuild";

const helperUrl = new URL("../src/components/collection-task/collectionTaskFieldAutoMapping.ts", import.meta.url);
const helperSource = await readFile(helperUrl, "utf8");
const transformed = await transform(helperSource, {
  format: "esm",
  loader: "ts",
  sourcemap: "inline",
  sourcefile: helperUrl.pathname,
  target: "node20",
});
const helper = await import(`data:text/javascript;base64,${Buffer.from(transformed.code).toString("base64")}`);
const { buildAutomaticFieldMappings } = helper;

function mappingPairs(rows) {
  return rows.map(({ sourceAlias, sourceField, targetField }) => ({ sourceAlias, sourceField, targetField }));
}

{
  const rows = buildAutomaticFieldMappings(
    ["id", "name", "created_at"],
    [{ sourceAlias: "src1", fields: ["id", "name", "created_at"] }],
  );
  assert.deepEqual(mappingPairs(rows), [
    { sourceAlias: "src1", sourceField: "id", targetField: "id" },
    { sourceAlias: "src1", sourceField: "name", targetField: "name" },
    { sourceAlias: "src1", sourceField: "created_at", targetField: "created_at" },
  ]);
  assert.ok(rows.every((row) => row.expression === "" && row.transformers.length === 0));
}

{
  const rows = buildAutomaticFieldMappings(
    ["USER_ID", "DisplayName"],
    [{ sourceAlias: "src1", fields: ["user_id", "displayname"] }],
  );
  assert.deepEqual(mappingPairs(rows), [
    { sourceAlias: "src1", sourceField: "user_id", targetField: "USER_ID" },
    { sourceAlias: "src1", sourceField: "displayname", targetField: "DisplayName" },
  ]);
}

{
  const rows = buildAutomaticFieldMappings(
    ["customerid", "CustomerID"],
    [{ sourceAlias: "src1", fields: ["CustomerID"] }],
  );
  assert.deepEqual(mappingPairs(rows), [
    { sourceAlias: "src1", sourceField: "", targetField: "customerid" },
    { sourceAlias: "src1", sourceField: "CustomerID", targetField: "CustomerID" },
  ]);
}

{
  const rows = buildAutomaticFieldMappings(
    ["id", "NAME", "missing"],
    [
      { sourceAlias: "left", fields: ["ID", "name"] },
      { sourceAlias: "right", fields: ["id", "NAME"] },
    ],
  );
  assert.deepEqual(mappingPairs(rows), [
    { sourceAlias: "right", sourceField: "id", targetField: "id" },
    { sourceAlias: "right", sourceField: "NAME", targetField: "NAME" },
    { sourceAlias: "left", sourceField: "", targetField: "missing" },
  ]);
}

{
  const rows = buildAutomaticFieldMappings(
    ["name", "NAME"],
    [{ sourceAlias: "src1", fields: ["Name"] }],
  );
  assert.equal(rows.filter((row) => row.sourceField === "Name").length, 1);
  assert.equal(rows.filter((row) => row.sourceField === "").length, 1);
}

{
  const rows = buildAutomaticFieldMappings(["id"], []);
  assert.deepEqual(mappingPairs(rows), [{ sourceAlias: "", sourceField: "", targetField: "id" }]);
}

{
  const targetFields = Array.from({ length: 300 }, (_, index) => `FIELD_${String(index).padStart(3, "0")}`);
  const sourceFields = targetFields.map((field, index) => (index % 2 === 0 ? field : field.toLowerCase()));
  const rows = buildAutomaticFieldMappings(targetFields, [{ sourceAlias: "src1", fields: sourceFields }]);
  assert.equal(rows.length, 300);
  assert.equal(rows.filter((row) => row.sourceField).length, 300);
  assert.deepEqual(rows.map((row) => row.targetField), targetFields);
  assert.deepEqual(rows.map((row) => row.sourceField), sourceFields);
}

console.log("collection task field auto-mapping tests passed");
