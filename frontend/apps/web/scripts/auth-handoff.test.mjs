import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { transform } from "esbuild";

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const source = await readFile(resolve(appRoot, "src/utils/authHandoff.ts"), "utf8");
const compiled = await transform(source, { loader: "ts", format: "esm", target: "es2020" });
const moduleUrl = `data:text/javascript;base64,${Buffer.from(compiled.code).toString("base64")}`;
const { resolveHandoffQueryValue, resolveSameOriginReturnPath } = await import(moduleUrl);

const origin = "http://127.0.0.1:8000";
const fallback = "/dfs/data-aggregation-studio/guide";

assert.equal(
  resolveSameOriginReturnPath("/other-system/orders?tab=active#detail", origin, fallback),
  "/other-system/orders?tab=active#detail",
);
assert.equal(resolveSameOriginReturnPath("https://evil.example/path", origin, fallback), fallback);
assert.equal(resolveSameOriginReturnPath("//evil.example/path", origin, fallback), fallback);
assert.equal(resolveSameOriginReturnPath("/\\evil.example/path", origin, fallback), fallback);
assert.equal(resolveSameOriginReturnPath(null, origin, fallback), fallback);

assert.equal(resolveHandoffQueryValue({ returnPath: "/preferred", redirect: "/legacy" }), "/preferred");
assert.equal(resolveHandoffQueryValue({ redirect: "/legacy" }), "/legacy");
assert.equal(resolveHandoffQueryValue({ redict: "/compatible" }), "/compatible");

console.log("Auth handoff policy tests passed.");
