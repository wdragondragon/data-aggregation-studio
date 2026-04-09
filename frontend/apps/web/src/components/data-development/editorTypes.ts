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
