import type { MetadataFieldDefinition, MetadataSchemaDefinition } from "@studio/api-sdk";

export type MetaSectionBinding = "TECHNICAL" | "BUSINESS";

export interface ModelMetaSection {
  key: string;
  schema: MetadataSchemaDefinition;
  title: string;
  description: string;
  binding: MetaSectionBinding;
  displayMode: "SINGLE" | "MULTIPLE";
  metaModelCode: string;
  fields: MetadataFieldDefinition[];
  collectionKey?: string;
}

export interface ModelQueryConditionState {
  fieldKey: string;
  operator: string;
  value?: unknown;
  valueTo?: unknown;
  multiValueText: string;
}

export interface ModelQueryGroupState {
  key: string;
  metaSchemaCode: string;
  rowMatchMode: "SAME_ITEM" | "ANY_ITEM";
  conditions: ModelQueryConditionState[];
}

export interface ModelDynamicFilterActions {
  appendQueryGroup: () => void;
  searchModels: () => void | Promise<void>;
  resetQueryFilters: () => void | Promise<void>;
  handleQuerySchemaChange: (group: ModelQueryGroupState) => void;
  appendQueryCondition: (group: ModelQueryGroupState) => void;
  removeQueryGroup: (groupKey: string) => void;
  querySchemaLabel: (schema: MetadataSchemaDefinition) => string;
  querySchemaFields: (group: ModelQueryGroupState) => MetadataFieldDefinition[];
  isMultipleQuerySchema: (group: ModelQueryGroupState) => boolean;
  handleQueryFieldChange: (group: ModelQueryGroupState, condition: ModelQueryConditionState) => void;
  queryConditionField: (group: ModelQueryGroupState, condition: ModelQueryConditionState) => MetadataFieldDefinition | undefined;
  queryConditionOperators: (group: ModelQueryGroupState, condition: ModelQueryConditionState) => string[];
  queryConditionInputValue: (value: unknown) => string | number | undefined;
  queryConditionBooleanValue: (value: unknown) => boolean | undefined;
  setQueryConditionValue: (condition: ModelQueryConditionState, value: string | number | boolean | null | undefined) => void;
  setQueryConditionValueTo: (condition: ModelQueryConditionState, value: string | number | null | undefined) => void;
  isNumericQueryField: (field?: MetadataFieldDefinition) => boolean;
  removeQueryCondition: (group: ModelQueryGroupState, index: number) => void;
}
