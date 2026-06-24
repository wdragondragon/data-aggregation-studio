import type { JavaImportHint, JavaMemberHint } from "@studio/api-sdk";

export interface SqlEditorTableHint {
  name: string;
  modelName?: string;
  columns: string[];
}

export interface SqlEditorHintSource {
  datasourceName?: string;
  datasourceTypeCode?: string;
  tables: SqlEditorTableHint[];
}

export type JavaImportHintLoader = (keyword: string, limit?: number) => Promise<JavaImportHint[]> | JavaImportHint[];
export type JavaMemberHintLoader = (
  className: string,
  keyword: string,
  staticOnly: boolean,
  limit?: number,
) => Promise<JavaMemberHint[]> | JavaMemberHint[];

export interface JavaEditorHintSource {
  loadImports: JavaImportHintLoader;
  loadMembers: JavaMemberHintLoader;
}
