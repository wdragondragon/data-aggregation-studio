import { readonly, shallowRef } from "vue";

export interface AssistantPageBusinessObject {
  type: string;
  path?: string;
  id?: string | number;
  name?: string;
  label?: string;
  typeCode?: string;
  physicalLocator?: string;
  status?: string;
  description?: string;
  metadata?: Record<string, unknown>;
}

export interface AssistantPageContextSnapshot {
  source: string;
  path: string;
  label?: string;
  summary?: string;
  activeObject?: AssistantPageBusinessObject;
  selectedObjects?: AssistantPageBusinessObject[];
  visibleObjects?: AssistantPageBusinessObject[];
  relatedObjects?: AssistantPageBusinessObject[];
  filters?: Record<string, unknown>;
  pagination?: Record<string, unknown>;
  updatedAt: string;
}

const assistantPageContext = shallowRef<AssistantPageContextSnapshot | null>(null);

export function setAssistantPageContext(context: Omit<AssistantPageContextSnapshot, "updatedAt">) {
  assistantPageContext.value = {
    ...context,
    updatedAt: new Date().toISOString(),
  };
}

export function clearAssistantPageContext(source?: string) {
  if (!source || assistantPageContext.value?.source === source) {
    assistantPageContext.value = null;
  }
}

export function useAssistantPageContext() {
  return readonly(assistantPageContext);
}
