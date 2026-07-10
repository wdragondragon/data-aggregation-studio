import type { ScriptType } from "@studio/api-sdk";

export interface ScriptEditorRegistryEntry {
  scriptType: ScriptType;
  languageId: string;
  supportsExecution: boolean;
  supportsSave: boolean;
  enableSqlHints: boolean;
  requiresDatasource: boolean;
}

export const SCRIPT_EDITOR_REGISTRY: Record<ScriptType, ScriptEditorRegistryEntry> = {
  SQL: {
    scriptType: "SQL",
    languageId: "sql",
    supportsExecution: true,
    supportsSave: true,
    enableSqlHints: true,
    requiresDatasource: true,
  },
  FLINK_QUESTION_SQL: {
    scriptType: "FLINK_QUESTION_SQL",
    languageId: "sql",
    supportsExecution: true,
    supportsSave: true,
    enableSqlHints: true,
    requiresDatasource: false,
  },
  JAVA: {
    scriptType: "JAVA",
    languageId: "java",
    supportsExecution: true,
    supportsSave: true,
    enableSqlHints: false,
    requiresDatasource: false,
  },
  PYTHON: {
    scriptType: "PYTHON",
    languageId: "python",
    supportsExecution: true,
    supportsSave: true,
    enableSqlHints: false,
    requiresDatasource: false,
  },
};

export function resolveScriptEditorEntry(scriptType: ScriptType): ScriptEditorRegistryEntry {
  return SCRIPT_EDITOR_REGISTRY[scriptType] ?? SCRIPT_EDITOR_REGISTRY.SQL;
}
