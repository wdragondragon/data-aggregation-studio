package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.assistant.AssistantActionDraft;
import com.jdragon.studio.dto.model.assistant.AssistantInputDefinition;
import com.jdragon.studio.dto.model.assistant.AssistantInputOption;
import com.jdragon.studio.dto.model.assistant.AssistantInterfaceDefinition;
import com.jdragon.studio.dto.model.assistant.AssistantKnowledgeCapability;
import com.jdragon.studio.dto.model.assistant.AssistantPlanRequest;
import com.jdragon.studio.dto.model.assistant.AssistantValueResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AssistantKnowledgeRegistry {

    public static final String CAPABILITY_COLLECTION_SINGLE_TABLE_CREATE = "collection.singleTable.create";

    private final List<AssistantKnowledgeCapability> capabilities;

    public AssistantKnowledgeRegistry() {
        List<AssistantKnowledgeCapability> items = new ArrayList<AssistantKnowledgeCapability>();
        items.add(collectionSingleTableCreateCapability());
        validateCapabilities(items);
        this.capabilities = Collections.unmodifiableList(items);
    }

    public List<AssistantKnowledgeCapability> listCapabilities() {
        return capabilities;
    }

    public AssistantKnowledgeCapability resolveCapabilityByCode(String capabilityCode) {
        if (capabilityCode == null || capabilityCode.trim().isEmpty()) {
            return null;
        }
        for (AssistantKnowledgeCapability capability : capabilities) {
            if (capabilityCode.trim().equals(capability.getCapabilityCode())) {
                return capability;
            }
        }
        return null;
    }

    public List<AssistantInputDefinition> resolveMissingInputs(AssistantKnowledgeCapability capability, Map<String, Object> collectedInputs) {
        return missingInputs(capability, collectedInputs);
    }

    public AssistantActionDraft createInitialDraft(AssistantKnowledgeCapability capability,
                                                   String message,
                                                   Map<String, Object> collectedInputs) {
        return buildInitialDraft(capability, message, collectedInputs);
    }

    public String latestUserMessage(AssistantPlanRequest request) {
        return latestMessage(request);
    }

    private List<AssistantInputDefinition> missingInputs(AssistantKnowledgeCapability capability, Map<String, Object> collectedInputs) {
        List<AssistantInputDefinition> missing = new ArrayList<AssistantInputDefinition>();
        Map<String, Object> inputs = collectedInputs == null ? Collections.<String, Object>emptyMap() : collectedInputs;
        for (AssistantInputDefinition input : capability.getRequiredInputs()) {
            if (!hasInput(inputs, input.getKey())) {
                missing.add(input);
            }
        }
        return missing;
    }

    private AssistantActionDraft buildInitialDraft(AssistantKnowledgeCapability capability,
                                                   String message,
                                                   Map<String, Object> collectedInputs) {
        AssistantActionDraft draft = new AssistantActionDraft();
        draft.setActionCode(capability.getCapabilityCode());
        draft.setSummary("根据知识库准备创建单表采集任务草稿，保存前必须先调用预览接口并由用户确认。");
        draft.setConfirmationLevel("PREVIEW_THEN_CONFIRM");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("rawMessage", message);
        payload.put("collectedInputs", collectedInputs == null ? new LinkedHashMap<String, Object>() : collectedInputs);
        payload.put("schedulePolicy", "DO_NOT_GENERATE_SCHEDULE");
        draft.setPayload(payload);
        Map<String, Object> preview = new LinkedHashMap<String, Object>();
        preview.put("nextStep", "前端根据 valueResolvers 调用接口查询候选值，并把候选值交给用户选择。");
        preview.put("writeBoundary", "后端助手接口不执行保存、发布、触发等变更操作。");
        draft.setPreview(preview);
        return draft;
    }

    private boolean hasInput(Map<String, Object> inputs, String key) {
        if (key == null || key.trim().isEmpty()) {
            return false;
        }
        if (inputs.containsKey(key) && !isBlankValue(inputs.get(key))) {
            return true;
        }
        String[] parts = key.split("\\.");
        Object cursor = inputs;
        for (String part : parts) {
            if (!(cursor instanceof Map<?, ?>)) {
                return false;
            }
            cursor = ((Map<?, ?>) cursor).get(part);
        }
        return !isBlankValue(cursor);
    }

    private boolean isBlankValue(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
    }

    private AssistantKnowledgeCapability collectionSingleTableCreateCapability() {
        AssistantKnowledgeCapability capability = new AssistantKnowledgeCapability();
        capability.setCapabilityCode(CAPABILITY_COLLECTION_SINGLE_TABLE_CREATE);
        capability.setCapabilityName("创建单表采集任务");
        capability.setDescription("通过 Studio 现有接口查询数据源、模型和运行参数，拼接并保存一个单源到单目标的采集任务草稿。");
        capability.getIntentExamples().add("创建 A 表到 B 表的单表采集");
        capability.getIntentExamples().add("把 xx 数据源的 xx 表采集到 yy 数据源的 yy 表");
        capability.getIntentExamples().add("配置一个全量单表同步任务");

        capability.getRequiredInputs().add(input("name", "任务名", "text", true, "采集任务显示名称", "例如：orders_to_ods_orders"));
        capability.getRequiredInputs().add(input("source.datasourceId", "源数据源", "select", true, "从可读数据源中选择", null));
        capability.getRequiredInputs().add(input("source.modelId", "源模型/表", "select", true, "从源数据源下的模型中选择", null));
        capability.getRequiredInputs().add(input("target.datasourceId", "目标数据源", "select", true, "从可写数据源中选择", null));
        capability.getRequiredInputs().add(input("target.modelId", "目标模型/表", "select", true, "从目标数据源下的模型中选择", null));
        capability.getRequiredInputs().add(input("fieldMappings", "字段映射", "fieldMapping", true, "默认同名字段自动映射，未匹配字段必须由用户确认", null));

        AssistantInputDefinition collectionMode = input("executionOptions.collectionMode", "采集模式", "select", false, "v1 默认全量采集", null);
        collectionMode.getOptions().add(option("全量采集", "FULL", "不生成增量游标参数"));
        capability.getOptionalInputs().add(collectionMode);

        capability.getInterfaces().add(api("studio.feature.list", "POST", "/api/v1/assistant/tools/execute {path=/catalog}", "读取可执行能力矩阵，判断可读/可写数据源类型", false, "path"));
        capability.getInterfaces().add(api("studio.feature.list", "POST", "/api/v1/assistant/tools/execute {path=/datasources}", "查询当前项目的数据源候选", false, "path"));
        capability.getInterfaces().add(api("studio.feature.list", "POST", "/api/v1/assistant/tools/execute {path=/models,datasourceId}", "查询指定数据源下的模型候选", false, "path", "source.datasourceId|target.datasourceId"));
        capability.getInterfaces().add(api("studio.feature.get", "POST", "/api/v1/assistant/tools/execute {path=/models,id}", "读取模型字段元数据", false, "path", "source.modelId|target.modelId"));
        capability.getInterfaces().add(api("studio.feature.list", "POST", "/api/v1/assistant/tools/execute {path=/catalog,view=runtimeOptionSchema}", "读取 reader/writer 运行参数元模型", false, "path", "view", "role", "datasourceType"));
        capability.getInterfaces().add(api("studio.feature.action", "POST", "/api/v1/assistant/tools/execute {path=/collection-tasks,action=preview}", "预览 JobContainer 配置，保存前必调", false, "path", "action", "payload"));
        capability.getInterfaces().add(api("studio.feature.action", "POST", "/api/v1/assistant/tools/execute {path=/collection-tasks,action=save}", "用户确认后保存采集任务草稿", true, "path", "action", "payload"));

        capability.getValueResolvers().add(resolver("source.datasourceId", "studio.feature.list", "用 {path:/datasources} 查询数据源候选后筛选 readable=true 的类型"));
        capability.getValueResolvers().add(resolver("target.datasourceId", "studio.feature.list", "用 {path:/datasources} 查询数据源候选后筛选 writable=true 的类型"));
        capability.getValueResolvers().add(resolver("source.modelId", "studio.feature.list", "用 {path:/models,datasourceId:source.datasourceId} 查询模型候选"));
        capability.getValueResolvers().add(resolver("target.modelId", "studio.feature.list", "用 {path:/models,datasourceId:target.datasourceId} 查询模型候选"));
        capability.getValueResolvers().add(resolver("fieldMappings", "studio.feature.get", "用 {path:/models,id:source.modelId|target.modelId} 读取字段并执行同名字段自动映射"));
        capability.getValueResolvers().add(resolver("readerOptions", "studio.feature.list", "用 {path:/catalog,view:runtimeOptionSchema,role:reader,datasourceType} 读取 reader 默认运行参数"));
        capability.getValueResolvers().add(resolver("writerOptions", "studio.feature.list", "用 {path:/catalog,view:runtimeOptionSchema,role:writer,datasourceType} 读取 writer 默认运行参数"));

        capability.getAssemblyRules().add("sourceBindings 固定为单元素数组，sourceAlias 固定为 src1。");
        capability.getAssemblyRules().add("targetBinding 使用用户选定的目标数据源和目标模型。");
        capability.getAssemblyRules().add("同名字段自动生成直接映射；未匹配目标字段必须让用户选择，不自动丢弃，不保存空映射。");
        capability.getAssemblyRules().add("executionOptions.collectionMode 默认 FULL。");
        capability.getAssemblyRules().add("不生成定时调度配置；如请求体必须携带 schedule，则仅传 {\"enabled\": false}。");
        capability.getAssemblyRules().add("保存前必须先通过 studio.feature.action {path:/collection-tasks,action:preview,payload} 预览，并展示预览摘要。");

        capability.getConfirmationPolicy().add("studio.feature.action {path:/collection-tasks,action:preview} 是保存前置条件。");
        capability.getConfirmationPolicy().add("collectionTasks.save 必须由用户确认后执行。");
        capability.getConfirmationPolicy().add("发布、触发、删除、修改配置等写操作必须进入统一确认队列。");
        capability.getConfirmationPolicy().add("AI 规划接口不得直接调用任何业务写接口。");
        return capability;
    }

    private AssistantInputDefinition input(String key, String label, String type, boolean required, String description, String placeholder) {
        AssistantInputDefinition input = new AssistantInputDefinition();
        input.setKey(key);
        input.setLabel(label);
        input.setType(type);
        input.setRequired(Boolean.valueOf(required));
        input.setDescription(description);
        input.setPlaceholder(placeholder);
        return input;
    }

    private AssistantInputOption option(String label, Object value, String description) {
        AssistantInputOption option = new AssistantInputOption();
        option.setLabel(label);
        option.setValue(value);
        option.setDescription(description);
        return option;
    }

    private AssistantInterfaceDefinition api(String code, String method, String path, String purpose, boolean mutation, String... requiredValues) {
        AssistantInterfaceDefinition api = new AssistantInterfaceDefinition();
        api.setInterfaceCode(code);
        api.setMethod(method);
        api.setPath(path);
        api.setPurpose(purpose);
        api.setMutation(Boolean.valueOf(mutation));
        if (requiredValues != null) {
            for (String value : requiredValues) {
                api.getRequiredValues().add(value);
            }
        }
        api.setResponseUsage(mutation ? "Only execute after explicit user confirmation." : "Use response as candidate/reference data.");
        return api;
    }

    private AssistantValueResolver resolver(String inputKey, String interfaceCode, String description) {
        AssistantValueResolver resolver = new AssistantValueResolver();
        resolver.setInputKey(inputKey);
        resolver.setInterfaceCode(interfaceCode);
        resolver.setDescription(description);
        return resolver;
    }

    private String latestMessage(AssistantPlanRequest request) {
        if (request == null) {
            return "";
        }
        if (request.getMessage() != null && !request.getMessage().trim().isEmpty()) {
            return request.getMessage().trim();
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            if (request.getMessages().get(i) == null) {
                continue;
            }
            String content = request.getMessages().get(i).getContent();
            if (content != null && !content.trim().isEmpty()) {
                return content.trim();
            }
        }
        return "";
    }

    private void validateCapabilities(List<AssistantKnowledgeCapability> items) {
        Set<String> codes = new LinkedHashSet<String>();
        for (AssistantKnowledgeCapability capability : items) {
            if (capability == null || capability.getCapabilityCode() == null || capability.getCapabilityCode().trim().isEmpty()) {
                throw new IllegalStateException("Assistant capability code is required");
            }
            if (!codes.add(capability.getCapabilityCode())) {
                throw new IllegalStateException("Duplicate assistant capability code: " + capability.getCapabilityCode());
            }
            if (capability.getInterfaces() == null || capability.getInterfaces().isEmpty()) {
                throw new IllegalStateException("Assistant capability interfaces are required: " + capability.getCapabilityCode());
            }
        }
    }
}
