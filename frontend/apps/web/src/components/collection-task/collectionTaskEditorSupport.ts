import type { CollectionTaskSaveRequest } from "@studio/api-sdk";

export interface CollectionTaskEditorForm extends Omit<CollectionTaskSaveRequest, "schedule"> {
  schedule: NonNullable<CollectionTaskSaveRequest["schedule"]>;
}

export type RuntimeOptionRole = "reader" | "writer";

export const fileReaderDatasourceTypes = new Set(["ftp", "sftp", "minio"]);
export const fileReaderDynamicFunctionFields = ["rootPath", "partition"];
export const httpReaderDynamicFunctionFields = ["header", "params", "requestBody"];
export const httpWriterDynamicFunctionFields = ["header", "params", "requestBody"];
export const fileWriterDatasourceTypes = new Set(["ftp", "sftp", "minio"]);
export const fileWriterDynamicFunctionFields = ["rootPath", "fileName", "efile.dataTime", "efile.planDate"];

export function createDefaultCollectionTaskForm(): CollectionTaskEditorForm {
  return {
    name: "",
    sourceBindings: [
      {
        sourceAlias: "src1",
        datasourceId: "",
        modelId: "",
        readerOptions: {},
        incremental: {
          enabled: false,
          incrModel: ">",
        },
      },
    ],
    targetBinding: {
      datasourceId: "",
      modelId: "",
      writerOptions: {},
    },
    fieldMappings: [],
    executionOptions: {
      collectionMode: "FULL",
      joinKeys: [],
      joinType: "LEFT",
    },
    schedule: {
      enabled: false,
      cronExpression: "0 */30 * * * ?",
      timezone: "Asia/Shanghai",
    },
  };
}
