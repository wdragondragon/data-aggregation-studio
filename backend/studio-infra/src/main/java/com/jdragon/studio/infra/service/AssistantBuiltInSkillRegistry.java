package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.assistant.AssistantPlanRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AssistantBuiltInSkillRegistry implements AssistantSkillProvider {

    private static final int MAX_CONTEXT_SKILLS = 8;

    private final List<Map<String, Object>> skills;

    public AssistantBuiltInSkillRegistry() {
        this.skills = Collections.unmodifiableList(buildSkills());
    }

    @Override
    public List<Map<String, Object>> assistantSkills(AssistantPlanRequest request) {
        String query = latestMessage(request);
        List<Map<String, Object>> matched = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> skill : skills) {
            if (matches(skill, query)) {
                matched.add(skill);
            }
        }
        if (matched.isEmpty()) {
            matched.addAll(skills);
        }
        return matched.size() > MAX_CONTEXT_SKILLS ? matched.subList(0, MAX_CONTEXT_SKILLS) : matched;
    }

    @AssistantBackendTool(
            code = "assistant.skills.search",
            name = "搜索内置助手技能",
            description = "按当前问题检索 Studio 内置 portable skill，只返回结构化文本知识，不执行业务写操作。"
    )
    public List<Map<String, Object>> searchSkills(AssistantPlanRequest request, Map<String, Object> params) {
        String query = params == null ? "" : stringValue(params.get("query"));
        AssistantPlanRequest effective = request;
        if (StringUtils.hasText(query)) {
            effective = new AssistantPlanRequest();
            effective.setMessage(query);
            if (request != null) {
                effective.setAssistantMode(request.getAssistantMode());
                effective.setResponseLanguage(request.getResponseLanguage());
                effective.setProtocolVersion(request.getProtocolVersion());
                effective.setContext(request.getContext());
                effective.setCollectedInputs(request.getCollectedInputs());
                effective.setToolResults(request.getToolResults());
                effective.setMessages(request.getMessages());
            }
        }
        return assistantSkills(effective);
    }

    public List<Map<String, Object>> allPortableSkills() {
        return skills;
    }

    private List<Map<String, Object>> buildSkills() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        result.add(skill(
                "studio-overview",
                "Studio module map",
                tags("overview", "modules", "studio"),
                "Studio covers datasource management, model center, collection tasks, workflows, data development, data services, protocol conversion, data quality, script environments, system management, and ops center.",
                "Explain what each Studio module does, then propose the next page or operation only when the user asks for action."));
        Map<String, Object> protocolSkill = skill(
                "assistant-protocol",
                "Studio assistant protocol",
                tags("protocol", "loop", "tool"),
                "Internal actions must be expressed with studio-assistant.v1 protocol blocks. Each block includes complete plan:{intent,basis,requiredObjects,nextActions}, complete loop:{status,autoContinue,questions,next,evidence,stopReason}, actions, and controls so any agent runtime can inspect the LLM decision before execution.",
                "Use protocol blocks for internal calls. Visible answers should stay natural and concise.");
        protocolSkill.put("protocolVersion", "studio-assistant.v1");
        protocolSkill.put("protocolFence", "studio-assistant-protocol");
        protocolSkill.put("protocolSchema", protocolSchema());
        protocolSkill.put("examples", protocolExamples());
        protocolSkill.put("providerPortability", list(
                "Do not depend on provider-specific function_call, tool_calls, response_format, or plugin payloads.",
                "Any agent platform can copy the fenced studio-assistant-protocol JSON and execute it with its own adapter.",
                "Visible text is independent from internal protocol blocks; consumers may hide the fenced block from users."));
        result.add(protocolSkill);
        result.add(skill(
                "assistant-loop",
                "Question decomposition loop",
                tags("question", "loop", "headless-engineer"),
                "For every user input, split compound asks into small questions, answer each one, infer the next helpful question, and continue by proposing the next safe step. Do not wait for the user when the next step can be derived from context.",
                "Render decomposed questions and next steps in chat, plan, or goal mode."));
        result.add(skill(
                "collection-single-table",
                "Single table collection task",
                tags("collection", "single-table", "sync"),
                "Creating a single-table collection task requires task name, source datasource, source model, target datasource, target model, field mappings, runtime options, preview, then explicit confirmation before save. Default collectionMode is FULL and schedule.enabled is false.",
                "Use datasource/model candidate queries and preview before save."));
        result.add(skill(
                "datasource-model-resolution",
                "Datasource and model resolution",
                tags("datasource", "model", "candidate"),
                "Datasource candidates come from datasource options. Model candidates come from datasource model options. Field metadata comes from model detail. If names are ambiguous, ask with controls instead of inventing ids.",
                "Resolve names into ids only from successful tool results or user selection."));
        result.add(skill(
                "runtime-cluster-placement",
                "Evidence-based runtime cluster placement",
                tags("runtime-cluster", "placement", "datasource", "model", "worker", "运行集群", "集群下推"),
                "Resolve runtimeClusterId from an explicit valid selection, a saved resource's inherited placement, or the intersection of all related datasource applicableClusterIds with project-authorized runtime clusters. Exactly one candidate may be pushed down automatically; zero candidates means a binding/authorization error; multiple candidates require a user select control. preferred, online status, list order, OMS identity, and fixed defaults are never selection evidence.",
                "Call /runtime-clusters with datasourceIds, modelIds, or applicableClusterIds when that evidence exists. For multiple candidates show id/code/name/status/preferred/allowManualOverride with paramKey assistantMemory.selectedEntity:/runtime-clusters and emit no business action until the user answers. A selected offline target must fail in place, never fail over automatically. Collection, quality, and workflow trigger actions omit runtimeClusterId to inherit saved placement. executeSavedScript omits payload to inherit; any explicit payload.runtimeClusterId is a manual override."));
        result.add(skill(
                "model-data-preview",
                "Cluster-scoped model data preview",
                tags("model", "preview", "runtime-cluster", "模型", "预览"),
                "Model preview reads real sample rows through a runtime cluster, so runtimeClusterId is required even when model id is already known. Ordinary model detail, fields, and lineage do not require preview placement.",
                "Identify the model datasource, call /runtime-clusters with modelIds or datasourceIds to intersect applicability and project authorization, apply the zero/one/many rule, list /models with the resolved runtimeClusterId to confirm the model is runnable there, then call /models preview through studio.feature.action with id, runtimeClusterId, and optional limit. Never use studio.feature.get view=preview or retry model lookup for a missing runtimeClusterId."));
        Map<String, Object> fieldMappingScript = skill(
                "field-mapping-python-helper",
                "Field mapping Python helper",
                tags("script", "python", "field-mapping", "portable-tool"),
                "A controlled offline Python helper can suggest source-to-target field mappings from two field lists. It never calls network services, never writes files, and must be executed only by a registered script executor.",
                "Use assistant.script.execute with entrypointId field-mapping-suggester only when sourceFields and targetFields are already available from toolResults or user input. If fields are missing, read model detail first or ask with controls.");
        fieldMappingScript.put("kind", "assistant.script.skill");
        fieldMappingScript.put("scriptEntrypoints", scriptEntrypoints());
        fieldMappingScript.put("safetyPolicy", list(
                "offline-only",
                "stdin-json-input",
                "stdout-json-output",
                "no-network",
                "no-file-write",
                "no-shell-expansion",
                "must-be-invoked-by-registered-executor"));
        result.add(fieldMappingScript);
        result.add(skill(
                "data-development",
                "Data development assistant",
                tags("data-development", "sql", "script"),
                "Data development can list directories/scripts, read script detail, execute SQL, execute model Flink SQL, execute unsaved script content, execute saved scripts, and save scripts. Execution and save operations require explicit confirmation.",
                "Before selecting SQL datasources or runnable models, reuse an explicitly selected valid cluster or call /runtime-clusters with the related datasourceIds/modelIds and apply the zero/one/many rule. Query /data-development with view=datasources and runtimeClusterId for SQL datasource candidates. For SQL execution, require runtimeClusterId, datasourceId, scriptType SQL, content, and maxRows. For model Flink SQL, require runtimeClusterId, scriptType FLINK_QUESTION_SQL, content, and executionConfig.modelIds. New Java/Python scripts without datasource or model evidence remain placement-required and must ask when project candidates are not unique."));
        result.add(skill(
                "service-and-protocol",
                "Service and protocol conversion assistant",
                tags("data-service", "data-ingestion-service", "protocol-conversion"),
                "Data services, data ingestion services, and protocol conversions support list/detail, field resolving, preview/debug, publish/offline, and subscription operations. Mutations require confirmation.",
                "For inspection use list/get first; for mutation produce a plan and confirmation step."));
        result.add(skill(
                "quality-and-ops",
                "Quality and ops assistant",
                tags("quality", "ops", "run"),
                "Quality modules include rules, tasks, task runs, metrics, asset risks, and issue handling. Ops center includes overview, queues, workers, service events, log events, and incidents.",
                "Use list/detail/log reads to summarize platform status before suggesting actions."));
        return result;
    }

    private List<Map<String, Object>> scriptEntrypoints() {
        List<Map<String, Object>> entrypoints = new ArrayList<Map<String, Object>>();
        Map<String, Object> entrypoint = new LinkedHashMap<String, Object>();
        entrypoint.put("schema", "studio.script-entrypoint.v1");
        entrypoint.put("id", "field-mapping-suggester");
        entrypoint.put("language", "python");
        entrypoint.put("runtime", "python3");
        entrypoint.put("entrypoint", "classpath:assistant/python/field_mapping_suggester.py");
        entrypoint.put("inputMode", "stdin-json");
        entrypoint.put("outputMode", "stdout-json");
        entrypoint.put("inputSchema", fieldMappingInputSchema());
        entrypoint.put("outputSchema", fieldMappingOutputSchema());
        entrypoints.add(entrypoint);
        return entrypoints;
    }

    private Map<String, Object> fieldMappingInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("required", list("sourceFields", "targetFields"));
        schema.put("sourceFields", "array of strings or field objects with name/fieldName/columnName");
        schema.put("targetFields", "array of strings or field objects with name/fieldName/columnName");
        return schema;
    }

    private Map<String, Object> fieldMappingOutputSchema() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("schema", "studio.script-result.v1");
        schema.put("data", list("mappings", "unresolvedTargetFields", "summary"));
        schema.put("mappingFields", list("targetField", "sourceField", "strategy", "confidence"));
        return schema;
    }

    private Map<String, Object> protocolSchema() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("protocol", "studio-assistant.v1");
        schema.put("required", list("protocol", "plan", "loop", "actions", "controls"));
        schema.put("protocolRequiredRule", "Every fenced studio-assistant-protocol block must explicitly set protocol to studio-assistant.v1. Blocks without this exact protocol value are invalid and must not be executed or rendered.");
        schema.put("planRequired", list("intent", "basis", "requiredObjects", "nextActions"));
        schema.put("planRequiredRule", "All four plan fields are required. intent must be non-empty text; basis and nextActions must be non-empty arrays; requiredObjects must be an explicit array and may be empty only when no object is needed.");
        schema.put("loopRequired", list("status", "autoContinue", "questions", "next", "evidence", "stopReason"));
        schema.put("loopRequiredRule", "Loop is required for every protocol block. status and stopReason must be non-empty text; autoContinue must be boolean; questions, next, and evidence must be explicit arrays.");
        schema.put("forbiddenFields", list("toolCalls", "backendToolCalls", "uiControls"));
        schema.put("forbiddenFieldsRule", "Provider-style or legacy execution fields are forbidden inside studio-assistant.v1. Use actions for executable calls and controls for conversational controls.");
        schema.put("actionRequired", list("type", "tool", "params"));
        schema.put("actionRequiredRule", "Every executable action must explicitly include type, tool, and params. Supported action types are frontendTool and backendTool; use params:{} when no parameters are needed.");
        schema.put("controlRequired", list("type", "title", "paramKey"));
        schema.put("controlRequiredRule", "Every conversational control must explicitly include type, title, and paramKey. select, choices, and confirm controls must include explicit options.");
        schema.put("plan", list(
                "intent: concise user intent as understood by the LLM",
                "basis: evidence used for the decision, such as current page, selected object, recent conversation, assistantCapabilities, or toolResults",
                "requiredObjects: business objects needed by the next action, with type/name/id/path when known",
                "nextActions: LLM-decided next steps, each with type/tool/path/action/reason when applicable"));
        schema.put("loop", list(
                "mode: chat | plan | goal",
                "status: thinking | tool_pending | user_confirmation_pending | waiting_for_user | completed",
                "autoContinue: true when the client should feed toolResults back automatically",
                "questions: decomposed question objects with id/input/status/output",
                "next: planned machine-continuable or user-decision steps",
                "evidence: optional references from toolResults",
                "stopReason: waiting_for_tool_result | waiting_for_user_confirmation | missing_user_input | answer_complete"));
        schema.put("actions", list(
                "frontendTool: {type:'frontendTool', tool:'assistant.context.observe|assistant.context.read|assistant.context.search|assistant.memory.select|studio.feature.list|get|action|studio.navigation.open|assistant.script.execute', reason, params}",
                "backendTool: {type:'backendTool', tool:'assistant.skills.search|studio.operations.search', reason, params}"));
        schema.put("contextToolContract", "assistant.context.observe reads broad page state; assistant.context.read reads current business context for a feature path including activeObject, selectedEntity, visible page objects, recent candidates, filters, and pagination; assistant.context.search searches existing pageContext/recent candidates/memory by path and keyword; assistant.memory.select selects only an existing candidate. None of them call business APIs.");
        schema.put("runtimeClusterContract", "Resolve runtimeClusterId in this order: explicit valid user/page selection; saved resource inheritance; otherwise the intersection of related datasource applicableClusterIds and project-authorized runtime clusters. Call /runtime-clusters with datasourceIds/modelIds/applicableClusterIds evidence. One candidate may be selected automatically; zero blocks as a binding/authorization error without a select; multiple candidates require a waiting_for_user select control that exposes id/code/name/status/preferred/allowManualOverride. Never choose by preferred, online status, list order, OMS identity, or a fixed default. Current cluster-required HTTP calls still receive the resolved id. Saved collection/quality/workflow triggers omit runtimeClusterId to inherit; executeSavedScript omits payload to inherit and treats every explicit payload.runtimeClusterId as manual override. Never fail over from an offline selected cluster.");
        schema.put("scriptToolContract", "assistant.script.execute runs registered scriptEntrypoints on a Studio Worker by entrypointId with stdin-json input and required runtimeClusterId; use an explicitly selected project-authorized runtime cluster and never output shell commands or arbitrary script text.");
        schema.put("controls", list(
                "choices/select/text/textarea/confirm controls for missing user decisions",
                "controls must explicitly include type, title, and paramKey",
                "select, choices, and confirm controls must include explicit options",
                "mutating Studio actions must be confirmed by the client before execution"));
        schema.put("toolResultContract", "Clients return summarized toolResults into the next assistant request; the assistant must decide from those results whether to emit another action/control or finish with loop.status=completed, autoContinue=false, actions=[], controls=[], stopReason=answer_complete. The gateway must not decide user intent or loop completion.");
        return schema;
    }

    private List<Map<String, Object>> protocolExamples() {
        List<Map<String, Object>> examples = new ArrayList<Map<String, Object>>();
        examples.add(protocolExample(
                "read datasource list",
                "{\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"List visible datasource records\",\"basis\":[\"User asked for datasources\",\"assistantCapabilities includes /datasources list\"],\"requiredObjects\":[{\"type\":\"feature\",\"path\":\"/datasources\"}],\"nextActions\":[{\"type\":\"tool\",\"tool\":\"studio.feature.list\",\"path\":\"/datasources\",\"reason\":\"Read datasource list before summarizing\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"tool_pending\",\"autoContinue\":true,\"questions\":[{\"id\":\"q1\",\"input\":\"Which datasource records are visible?\",\"status\":\"needs_tool\",\"output\":\"pending tool result\"}],\"next\":[{\"id\":\"step1\",\"type\":\"tool\",\"status\":\"pending\",\"description\":\"Read datasource list and summarize it.\"}],\"evidence\":[],\"stopReason\":\"waiting_for_tool_result\"},\"actions\":[{\"type\":\"frontendTool\",\"tool\":\"studio.feature.list\",\"reason\":\"Read datasource list\",\"params\":{\"path\":\"/datasources\",\"pageNo\":1,\"pageSize\":20}}],\"controls\":[]}"));
        examples.add(protocolExample(
                "ask for missing datasource id",
                "{\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Prepare SQL execution but datasource is missing\",\"basis\":[\"User asked to execute SQL\",\"No datasourceId exists in context or memory\"],\"requiredObjects\":[{\"type\":\"datasource\",\"name\":\"execution datasource\",\"status\":\"missing\"}],\"nextActions\":[{\"type\":\"control\",\"reason\":\"Ask user to choose datasource\"}]},\"loop\":{\"mode\":\"plan\",\"status\":\"waiting_for_user\",\"autoContinue\":false,\"questions\":[{\"id\":\"q1\",\"input\":\"Which datasource should execute the SQL?\",\"status\":\"needs_user\",\"output\":\"waiting for selection\"}],\"next\":[{\"id\":\"step1\",\"type\":\"control\",\"status\":\"pending\",\"description\":\"Ask the user to choose a datasource.\"}],\"evidence\":[],\"stopReason\":\"missing_user_input\"},\"actions\":[],\"controls\":[{\"type\":\"select\",\"title\":\"选择数据源\",\"paramKey\":\"payload.datasourceId\",\"options\":[{\"label\":\"mock_mysql\",\"value\":\"11\"},{\"label\":\"mock_postgres\",\"value\":\"12\"}]}]}"));
        examples.add(protocolExample(
                "read runtime clusters before execution",
                "{\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Resolve execution runtime cluster\",\"basis\":[\"Worker execution requires runtimeClusterId\",\"Datasource 11 and model 5 are the related resources\"],\"requiredObjects\":[{\"type\":\"runtimeCluster\",\"path\":\"/runtime-clusters\",\"status\":\"missing\"}],\"nextActions\":[{\"type\":\"tool\",\"tool\":\"studio.feature.list\",\"path\":\"/runtime-clusters\",\"reason\":\"Intersect project authorization with related resource applicability\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"tool_pending\",\"autoContinue\":true,\"questions\":[{\"id\":\"q1\",\"input\":\"Which authorized applicable runtime cluster should execute the query?\",\"status\":\"needs_tool\",\"output\":\"pending runtime cluster options\"}],\"next\":[{\"id\":\"step1\",\"type\":\"tool\",\"status\":\"pending\",\"description\":\"Resolve the common runtime cluster candidates.\"}],\"evidence\":[],\"stopReason\":\"waiting_for_tool_result\"},\"actions\":[{\"type\":\"frontendTool\",\"tool\":\"studio.feature.list\",\"reason\":\"Resolve execution placement\",\"params\":{\"path\":\"/runtime-clusters\",\"datasourceIds\":[11],\"modelIds\":[5]}}],\"controls\":[]}"));
        examples.add(protocolExample(
                "ask when runtime cluster candidates are ambiguous",
                "{\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Resolve ambiguous runtime placement\",\"basis\":[\"The filtered /runtime-clusters tool result contains two candidates\",\"Neither candidate was explicitly selected by the user\"],\"requiredObjects\":[{\"type\":\"runtimeCluster\",\"status\":\"ambiguous\"}],\"nextActions\":[{\"type\":\"control\",\"reason\":\"Ask the user instead of choosing by metadata\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"waiting_for_user\",\"autoContinue\":false,\"questions\":[{\"id\":\"q1\",\"input\":\"Which runtime cluster should be used?\",\"status\":\"needs_user\",\"output\":\"waiting for explicit selection\"}],\"next\":[{\"id\":\"step1\",\"type\":\"control\",\"status\":\"pending\",\"description\":\"Wait for a runtime cluster selection.\"}],\"evidence\":[{\"type\":\"toolResult\",\"path\":\"/runtime-clusters\",\"candidateCount\":2}],\"stopReason\":\"missing_user_input\"},\"actions\":[],\"controls\":[{\"type\":\"select\",\"title\":\"选择运行集群\",\"paramKey\":\"assistantMemory.selectedEntity:/runtime-clusters\",\"options\":[{\"label\":\"cluster-a | code=CLUSTER_A | id=101 | status=ONLINE\",\"value\":\"101\",\"description\":\"preferred=true; allowManualOverride=false\"},{\"label\":\"cluster-b | code=CLUSTER_B | id=202 | status=OFFLINE\",\"value\":\"202\",\"description\":\"preferred=false; allowManualOverride=true\"}]}]}"));
        examples.add(protocolExample(
                "preview a model in the selected runtime cluster",
                "{\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Preview registered model rows\",\"basis\":[\"Model 5 was selected from /models candidates for runtime cluster 7\"],\"requiredObjects\":[{\"type\":\"runtimeCluster\",\"id\":\"7\"},{\"type\":\"model\",\"id\":\"5\"}],\"nextActions\":[{\"type\":\"tool\",\"tool\":\"studio.feature.action\",\"path\":\"/models\",\"action\":\"preview\",\"reason\":\"Read model sample rows in the selected cluster\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"tool_pending\",\"autoContinue\":true,\"questions\":[{\"id\":\"q1\",\"input\":\"What sample rows does the selected model return?\",\"status\":\"needs_tool\",\"output\":\"pending preview result\"}],\"next\":[{\"id\":\"step1\",\"type\":\"tool\",\"status\":\"pending\",\"description\":\"Preview the selected model in runtime cluster 7.\"}],\"evidence\":[],\"stopReason\":\"waiting_for_tool_result\"},\"actions\":[{\"type\":\"frontendTool\",\"tool\":\"studio.feature.action\",\"reason\":\"Preview registered model rows\",\"params\":{\"path\":\"/models\",\"action\":\"preview\",\"id\":5,\"runtimeClusterId\":7,\"limit\":20}}],\"controls\":[]}"));
        examples.add(protocolExample(
                "run registered field mapping script",
                "{\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Suggest deterministic field mappings\",\"basis\":[\"sourceFields and targetFields are already available\",\"Runtime cluster 7 is selected from project-authorized candidates\"],\"requiredObjects\":[{\"type\":\"scriptEntrypoint\",\"id\":\"field-mapping-suggester\"},{\"type\":\"runtimeCluster\",\"id\":\"7\"}],\"nextActions\":[{\"type\":\"tool\",\"tool\":\"assistant.script.execute\",\"reason\":\"Run registered helper\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"tool_pending\",\"autoContinue\":true,\"questions\":[{\"id\":\"q1\",\"input\":\"Suggest field mappings from known fields.\",\"status\":\"needs_tool\",\"output\":\"pending script result\"}],\"next\":[{\"id\":\"step1\",\"type\":\"tool\",\"status\":\"pending\",\"description\":\"Run the registered field mapping helper.\"}],\"evidence\":[],\"stopReason\":\"waiting_for_tool_result\"},\"actions\":[{\"type\":\"frontendTool\",\"tool\":\"assistant.script.execute\",\"reason\":\"Suggest deterministic field mappings\",\"params\":{\"entrypointId\":\"field-mapping-suggester\",\"input\":{\"sourceFields\":[\"id\",\"order_no\"],\"targetFields\":[\"ID\",\"orderNo\"]},\"runtimeClusterId\":7}}],\"controls\":[]}"));
        examples.add(protocolExample(
                "finish after sufficient toolResults",
                "{\"protocol\":\"studio-assistant.v1\",\"plan\":{\"intent\":\"Finish answer after datasource tool result\",\"basis\":[\"toolResults already contain the requested datasource list\",\"No more Studio operation is needed\"],\"requiredObjects\":[{\"type\":\"feature\",\"path\":\"/datasources\"}],\"nextActions\":[{\"type\":\"final\",\"reason\":\"Return final answer without another tool call\"}]},\"loop\":{\"mode\":\"goal\",\"status\":\"completed\",\"autoContinue\":false,\"questions\":[{\"id\":\"q1\",\"input\":\"Are toolResults sufficient to answer?\",\"status\":\"answered\",\"output\":\"yes\"}],\"next\":[{\"id\":\"step1\",\"type\":\"final\",\"status\":\"done\",\"description\":\"Return final answer.\"}],\"evidence\":[{\"type\":\"toolResult\",\"interfaceCode\":\"studio.feature.list\",\"path\":\"/datasources\"}],\"stopReason\":\"answer_complete\"},\"actions\":[],\"controls\":[]}"));
        return examples;
    }

    private Map<String, Object> protocolExample(String title, String payload) {
        Map<String, Object> example = new LinkedHashMap<String, Object>();
        example.put("title", title);
        example.put("fence", "studio-assistant-protocol");
        example.put("payload", payload);
        return example;
    }

    private Map<String, Object> skill(String id,
                                      String title,
                                      List<String> tags,
                                      String content,
                                      String instruction) {
        Map<String, Object> skill = new LinkedHashMap<String, Object>();
        skill.put("schema", "studio.skill.v1");
        skill.put("portable", Boolean.TRUE);
        skill.put("id", id);
        skill.put("title", title);
        skill.put("tags", tags);
        skill.put("content", content);
        skill.put("instruction", instruction);
        skill.put("agentUsage", "Can be copied into another agent platform as a platform skill card. The consuming agent should treat content as product knowledge and instruction as behavior guidance.");
        return skill;
    }

    private List<String> tags(String... values) {
        return Arrays.asList(values);
    }

    private List<String> list(String... values) {
        return Arrays.asList(values);
    }

    private boolean matches(Map<String, Object> skill, String query) {
        if (!StringUtils.hasText(query)) {
            return true;
        }
        String text = (stringValue(skill.get("id")) + "\n"
                + stringValue(skill.get("title")) + "\n"
                + stringValue(skill.get("tags")) + "\n"
                + stringValue(skill.get("content"))).toLowerCase(Locale.ROOT);
        for (String token : tokens(query)) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> tokens(String query) {
        Set<String> result = new LinkedHashSet<String>();
        String normalized = stringValue(query)
                .replaceAll("[^\\p{IsHan}A-Za-z0-9_]+", " ")
                .toLowerCase(Locale.ROOT)
                .trim();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                result.add(token);
            }
        }
        if (result.isEmpty() && StringUtils.hasText(query)) {
            result.add(query.trim().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private String latestMessage(AssistantPlanRequest request) {
        if (request == null) {
            return "";
        }
        if (StringUtils.hasText(request.getMessage())) {
            return request.getMessage().trim();
        }
        if (request.getMessages() == null) {
            return "";
        }
        for (int index = request.getMessages().size() - 1; index >= 0; index--) {
            if (request.getMessages().get(index) != null
                    && StringUtils.hasText(request.getMessages().get(index).getContent())) {
                return request.getMessages().get(index).getContent().trim();
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
