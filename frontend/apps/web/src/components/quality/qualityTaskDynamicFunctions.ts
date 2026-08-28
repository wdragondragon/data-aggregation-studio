export { dynamicFunctionCatalog } from "@studio/meta-form";
export type { DynamicFunctionParamSchema, DynamicFunctionSchema } from "@studio/meta-form";

export type DynamicFunctionInsertTarget =
  | { type: "whereClause" }
  | { type: "binding"; key: string };
