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
public class AssistantStudioOperationRegistry implements AssistantSkillProvider {

    private static final int MAX_CONTEXT_OPERATIONS = 10;

    private final List<Map<String, Object>> operations;

    public AssistantStudioOperationRegistry() {
        this.operations = Collections.unmodifiableList(buildOperations());
    }

    @Override
    public List<Map<String, Object>> assistantSkills(AssistantPlanRequest request) {
        List<Map<String, Object>> matched = searchOperationCards(latestMessage(request), null);
        if (matched.size() > MAX_CONTEXT_OPERATIONS) {
            return matched.subList(0, MAX_CONTEXT_OPERATIONS);
        }
        return matched;
    }

    @AssistantBackendTool(
            code = "studio.operations.search",
            name = "Search Studio operation catalog",
            description = "Search portable Studio feature and operation cards. The result only describes controlled frontend tools and never executes business mutations."
    )
    public List<Map<String, Object>> searchOperations(AssistantPlanRequest request, Map<String, Object> params) {
        String query = params == null ? "" : stringValue(params.get("query"));
        String path = params == null ? "" : normalizePath(stringValue(params.get("path")));
        int limit = intValue(params == null ? null : params.get("limit"), MAX_CONTEXT_OPERATIONS);
        List<Map<String, Object>> matched = searchOperationCards(StringUtils.hasText(query) ? query : latestMessage(request), path);
        return matched.size() > limit ? matched.subList(0, limit) : matched;
    }

    public List<Map<String, Object>> allOperations() {
        return operations;
    }

    public List<Map<String, Object>> allOperationSkillCards() {
        List<Map<String, Object>> cards = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> operation : operations) {
            cards.add(toSkillCard(operation));
        }
        return cards;
    }

    private List<Map<String, Object>> searchOperationCards(String query, String path) {
        List<Map<String, Object>> matchedOperations = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> operation : operations) {
            if (StringUtils.hasText(path) && !path.equals(operation.get("path"))) {
                continue;
            }
            if (!StringUtils.hasText(query) || matchScore(operation, query) > 0) {
                matchedOperations.add(operation);
            }
        }
        if (matchedOperations.isEmpty() && !StringUtils.hasText(path)) {
            matchedOperations.addAll(operations);
        } else if (StringUtils.hasText(query)) {
            Collections.sort(matchedOperations, (left, right) ->
                    Integer.compare(matchScore(right, query), matchScore(left, query)));
        }
        List<Map<String, Object>> matchedCards = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> operation : matchedOperations) {
            matchedCards.add(toSkillCard(operation));
        }
        return matchedCards;
    }

    private int matchScore(Map<String, Object> operation, String query) {
        int score = 0;
        String text = searchableText(operation);
        for (String token : tokens(query)) {
            if (isIntentToken(token)) {
                continue;
            }
            if (text.contains(token)) {
                score++;
            }
        }
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (normalizedQuery.contains(stringValue(operation.get("path")).toLowerCase(Locale.ROOT))) {
            score += 5;
        }
        String label = stringValue(operation.get("label")).toLowerCase(Locale.ROOT);
        if (StringUtils.hasText(label) && normalizedQuery.contains(label)) {
            score += 3;
        }
        for (String term : directMatchTerms(operation)) {
            if (normalizedQuery.contains(term)) {
                score += term.startsWith("/") ? 5 : 3;
            }
        }
        return score;
    }

    private List<String> directMatchTerms(Map<String, Object> operation) {
        List<String> terms = new ArrayList<String>();
        addDirectTerm(terms, operation.get("path"));
        addDirectTerm(terms, operation.get("label"));
        addDirectTerm(terms, operation.get("capabilityCode"));
        addDirectTerm(terms, operation.get("group"));
        Object tags = operation.get("tags");
        if (tags instanceof Iterable<?>) {
            for (Object tag : (Iterable<?>) tags) {
                addDirectTerm(terms, tag);
            }
        } else {
            addDirectTerm(terms, tags);
        }
        return terms;
    }

    private void addDirectTerm(List<String> terms, Object value) {
        String term = stringValue(value).trim().toLowerCase(Locale.ROOT);
        if (term.length() >= 2) {
            terms.add(term);
        }
    }

    private Map<String, Object> toSkillCard(Map<String, Object> operation) {
        Map<String, Object> skill = new LinkedHashMap<String, Object>();
        skill.put("schema", "studio.skill.v1");
        skill.put("portable", Boolean.TRUE);
        skill.put("kind", "studio.operation.catalog");
        skill.put("id", "studio-operation-" + operation.get("capabilityCode"));
        skill.put("title", operation.get("label") + " operation catalog");
        skill.put("tags", operation.get("tags"));
        skill.put("path", operation.get("path"));
        skill.put("content", "Path: " + operation.get("path")
                + ". Description: " + operation.get("description")
                + ". Read tools: " + operation.get("readTools")
                + ". Write policy: " + operation.get("writePolicy"));
        skill.put("instruction", "Use studio-assistant.v1 actions with studio.navigation.open, studio.feature.list, studio.feature.get, or studio.feature.action. Mutating operations require explicit user confirmation through the frontend.");
        skill.put("agentUsage", "Portable Studio operation card. Other agent platforms can copy it as feature knowledge and use the declared custom protocol instead of provider-specific function calling.");
        skill.put("operation", operation);
        skill.put("readTools", operation.get("readTools"));
        skill.put("writePolicy", operation.get("writePolicy"));
        return skill;
    }

    private List<Map<String, Object>> buildOperations() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        result.add(operation("Project dashboard", "studio.dashboard.overview", "Overview", "/dashboard",
                "Read project overview for datasources, models, tasks, runs, and platform status.",
                true, false, "Read-only overview.",
                "dashboard", "overview", "kanban", "首页", "看板", "概览"));
        result.add(operation("Access center", "studio.accessCenter.overview", "Workspace", "/access-center",
                "Inspect project access requests and workspace entry state.",
                true, false, "Access apply or cancel actions require explicit confirmation.",
                "access", "workspace", "申请", "工作区"));
        result.add(operation("Catalog capabilities", "studio.catalog.capabilities", "Metadata", "/catalog",
                "Inspect supported datasource types, read/write capabilities, and runtime option schemas.",
                true, false, "Read-only catalog.",
                "catalog", "capability", "datasource", "能力", "目录"));
        result.add(operation("Runtime cluster options", "studio.runtimeClusters.options", "Operations", "/runtime-clusters",
                "List runtime clusters authorized for the current project before selecting execution placement.",
                true, false, "Read-only project runtime cluster options.",
                "runtime cluster", "placement", "execution cluster", "运行集群", "执行集群"));
        result.add(operation("Metadata schemas", "studio.metadata.schemas", "Metadata", "/metadata",
                "List and inspect meta models, fields, and schema definitions.",
                true, true, "Draft save, publish, and sync actions require explicit confirmation.",
                "metadata", "schema", "field", "元数据", "元模型", "字段"));
        result.add(operation("Datasources", "studio.datasources.manage", "Data assets", "/datasources",
                "List datasource connections, inspect details, review connection test history, and discover real physical tables or views inside a selected datasource.",
                true, true, "Save, test, discover, or delete datasource actions require explicit confirmation; destructive actions are not exposed.",
                "datasource", "connection", "source", "physical table", "real table", "table discovery", "数据源", "连接", "真实表", "物理表", "库表", "表发现"));
        result.add(operation("Models", "studio.models.manage", "Data assets", "/models",
                "List registered data models, inspect modeled table fields, preview model data, and review lineage.",
                true, true, "Model save, sync, index rebuild, and lineage edits require explicit confirmation.",
                "model", "registered model", "modeled table", "lineage", "preview", "模型", "已登记模型", "数据模型", "血缘"));
        result.add(operation("Model statistics", "studio.modelStatistics.query", "Data assets", "/statistics",
                "Read model statistics configuration and charts.",
                true, false, "Read-only statistics.",
                "statistics", "metric", "统计", "图表"));
        result.add(operation("Field mapping rules", "studio.fieldMappingRules.manage", "Collection", "/field-mapping-rules",
                "List and inspect reusable field mapping rules.",
                true, true, "Save actions require explicit confirmation; delete actions are not exposed.",
                "field", "mapping", "rule", "字段映射", "规则"));
        result.add(operation("Collection tasks", "studio.collectionTasks.manage", "Collection", "/collection-tasks",
                "List, inspect, preview, draft, publish, schedule, and trigger collection tasks.",
                true, true, "Save, publish, trigger, schedule, and cursor reset require explicit confirmation. Schedules are disabled by default.",
                "collection", "sync", "task", "single-table", "采集", "同步", "任务", "单表"));
        result.add(operation("Collection task runs", "studio.collectionTaskRuns.query", "Collection", "/collection-task-runs",
                "List collection task run records and inspect logs.",
                true, true, "Read-only run inspection.",
                "collection", "run", "log", "采集运行", "日志"));
        result.add(operation("Run metrics", "studio.runMetrics.query", "Operations", "/run-metrics",
                "Read task run metric overview by time, execution type, or status.",
                true, false, "Read-only metrics.",
                "run", "metric", "运行指标"));
        result.add(operation("Data development", "studio.dataDevelopment.manage", "Development", "/data-development",
                "List script directories, inspect scripts, execute SQL, run scripts, and save scripts.",
                true, true, "SQL/script execution and saves require explicit confirmation and concrete datasource/script inputs.",
                "development", "sql", "script", "python", "java", "数据开发", "脚本"));
        result.add(operation("Workflows", "studio.workflows.manage", "Orchestration", "/workflows",
                "List workflows, inspect definitions, bind tasks, publish, schedule, and trigger workflows.",
                true, true, "Save, publish, trigger, and schedule actions require explicit confirmation.",
                "workflow", "orchestration", "dag", "工作流", "编排"));
        result.add(operation("Runs", "studio.runs.query", "Operations", "/runs",
                "List workflow, collection, and quality run records with details and logs.",
                true, true, "Read-only run inspection.",
                "run", "record", "log", "运行", "实例"));
        result.add(operation("Data services", "studio.dataServices.manage", "Services", "/data-services",
                "List and inspect data services, API previews, subscriptions, metrics, and debug flows.",
                true, true, "Save, publish, offline, debug, and subscription mutations require explicit confirmation.",
                "service", "api", "subscription", "数据服务", "共享"));
        result.add(operation("Data ingestion services", "studio.dataIngestionServices.manage", "Services", "/data-ingestion-services",
                "List and inspect data ingestion services and subscriptions.",
                true, true, "Save, publish, offline, debug, and subscription mutations require explicit confirmation.",
                "ingestion", "service", "subscription", "接入服务"));
        result.add(operation("Protocol conversions", "studio.protocolConversions.manage", "Services", "/protocol-conversions",
                "List and inspect protocol conversion services and subscriptions.",
                true, true, "Save, publish, offline, debug, and subscription mutations require explicit confirmation.",
                "protocol", "conversion", "service", "协议转换"));
        result.add(operation("Data ingestion metrics", "studio.dataIngestionMetrics.query", "Services", "/data-ingestion-metrics",
                "Read data ingestion service metrics, API statistics, and access logs.",
                true, false, "Read-only metrics and logs.",
                "ingestion", "metric", "access-log", "接入指标"));
        result.add(operation("Data service metrics", "studio.dataServiceMetrics.query", "Services", "/data-service-metrics",
                "Read data service metrics, API statistics, and access logs.",
                true, false, "Read-only metrics and logs.",
                "service", "metric", "access-log", "服务指标"));
        result.add(operation("Quality rules", "studio.qualityRules.manage", "Quality", "/quality-rules",
                "List and inspect quality rules and rule candidates.",
                true, true, "Save, enable, disable, and delete rule actions require explicit confirmation; destructive actions are not exposed.",
                "quality", "rule", "质量规则"));
        result.add(operation("Quality tasks", "studio.qualityTasks.manage", "Quality", "/quality-tasks",
                "List, inspect, preview, publish, schedule, and trigger quality tasks.",
                true, true, "Save, publish, trigger, and schedule actions require explicit confirmation.",
                "quality", "task", "质量任务"));
        result.add(operation("Quality task runs", "studio.qualityTaskRuns.query", "Quality", "/quality-task-runs",
                "List quality task run records and inspect logs.",
                true, true, "Read-only run inspection.",
                "quality", "run", "log", "质量运行"));
        result.add(operation("Quality metrics", "studio.qualityMetrics.query", "Quality", "/quality-metrics",
                "Read quality metrics, asset risks, and issue details.",
                true, true, "Issue assignment, status, severity, and comment updates require explicit confirmation.",
                "quality", "metric", "issue", "asset-risk", "质量指标", "问题"));
        result.add(operation("Notifications", "studio.notifications.manage", "Operations", "/notifications",
                "List notifications, read notification snapshots, and mark notifications as read.",
                true, false, "Mark-read actions require explicit confirmation.",
                "notification", "inbox", "unread", "通知", "消息"));
        result.add(operation("System management", "studio.system.manage", "System", "/system",
                "List tenants, projects, members, roles, permissions, and resource shares.",
                true, false, "System save, approval, reject, and delete actions require explicit confirmation.",
                "system", "tenant", "project", "member", "系统", "租户", "项目"));
        result.add(operation("Script environments", "studio.scriptEnvironments.manage", "Development", "/script-environments",
                "List and inspect script runtime environments and dependencies.",
                true, true, "Save, enable, disable, and refresh actions require explicit confirmation.",
                "script", "environment", "dependency", "脚本环境"));
        result.add(operation("Ops center", "studio.opsCenter.query", "Operations", "/ops-center",
                "Read operation center overview, queues, workers, service events, log events, and incidents.",
                true, false, "Read-only ops inspection.",
                "ops", "worker", "queue", "incident", "运维", "运行中心"));
        result.add(operation("Alert center", "studio.alerts.manage", "Operations", "/alerts",
                "Read alert summaries, rules, incidents, channels, and delivery records; operate existing alert objects.",
                true, false, "Enable, disable, test, acknowledge, close, and retry actions require explicit confirmation.",
                "alert", "incident", "webhook", "告警", "告警中心"));
        return result;
    }

    private Map<String, Object> operation(String label,
                                          String capabilityCode,
                                          String group,
                                          String path,
                                          String description,
                                          boolean supportsList,
                                          boolean supportsGet,
                                          String writePolicy,
                                          String... tags) {
        Map<String, Object> operation = new LinkedHashMap<String, Object>();
        operation.put("schema", "studio.operation.v1");
        operation.put("portable", Boolean.TRUE);
        operation.put("label", label);
        operation.put("capabilityCode", capabilityCode);
        operation.put("group", group);
        operation.put("path", path);
        operation.put("description", description);
        operation.put("supportsList", Boolean.valueOf(supportsList));
        operation.put("supportsGet", Boolean.valueOf(supportsGet));
        operation.put("writePolicy", writePolicy);
        operation.put("tags", tags(tags));
        operation.put("readTools", readTools(label, path, supportsList, supportsGet));
        operation.put("defaultFrontendActions", defaultFrontendActions(path, supportsList));
        operation.put("featureActions", featureActions(path));
        operation.put("agentUsage", "Portable Studio operation definition for custom-protocol agents.");
        return operation;
    }

    private List<Map<String, Object>> readTools(String label, String path, boolean supportsList, boolean supportsGet) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        result.add(readTool(
                "studio.navigation.open",
                path,
                "Open " + label + " page.",
                list("path"),
                emptyList(),
                emptyMap()));
        if (supportsList) {
            result.add(readTool(
                    "studio.feature.list",
                    path,
                    listToolPurpose(path, label),
                    list("path"),
                    listOptionalValues(path),
                    listDefaultParams(path)));
        }
        if (supportsGet) {
            result.add(readTool(
                    "studio.feature.get",
                    path,
                    getToolPurpose(path, label),
                    getRequiredValues(path),
                    getOptionalValues(path),
                    emptyMap()));
        }
        return result;
    }

    private Map<String, Object> readTool(String tool,
                                         String path,
                                         String purpose,
                                         List<String> requiredValues,
                                         List<String> optionalValues,
                                         Map<String, Object> defaultParams) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("type", "frontendTool");
        item.put("tool", tool);
        item.put("purpose", purpose);
        item.put("mutation", Boolean.FALSE);
        item.put("requiredValues", requiredValues);
        item.put("optionalValues", optionalValues);
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("path", path);
        item.put("params", params);
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        defaults.put("path", path);
        defaults.putAll(defaultParams);
        item.put("defaultParams", defaults);
        return item;
    }

    private String listToolPurpose(String path, String label) {
        if ("/dashboard".equals(path)) {
            return "Read the current project dashboard overview.";
        }
        if ("/access-center".equals(path)) {
            return "Read workspace access overview and request state.";
        }
        if ("/catalog".equals(path)) {
            return "Read datasource capability matrix and runtime option capability catalog.";
        }
        if ("/runtime-clusters".equals(path)) {
            return "List runtime clusters authorized for the current project.";
        }
        if ("/metadata".equals(path)) {
            return "List meta model definitions.";
        }
        if ("/datasources".equals(path)) {
            return "Page datasource connections.";
        }
        if ("/models".equals(path)) {
            return "Page data models or search model candidates.";
        }
        if ("/statistics".equals(path)) {
            return "Read model statistic options or metric charts.";
        }
        if ("/field-mapping-rules".equals(path)) {
            return "Page reusable field mapping rules.";
        }
        if ("/collection-tasks".equals(path)) {
            return "Page collection tasks.";
        }
        if ("/collection-task-runs".equals(path)) {
            return "Page collection task run records.";
        }
        if ("/run-metrics".equals(path)) {
            return "Read task run metric overview.";
        }
        if ("/data-development".equals(path)) {
            return "Read script tree, script list, or datasource candidates for data development.";
        }
        if ("/workflows".equals(path)) {
            return "Page workflow definitions.";
        }
        if ("/runs".equals(path)) {
            return "Page workflow, collection, and quality run records.";
        }
        if ("/data-services".equals(path)) {
            return "Page data services.";
        }
        if ("/data-ingestion-services".equals(path)) {
            return "Page data ingestion services.";
        }
        if ("/protocol-conversions".equals(path)) {
            return "Page protocol conversion services.";
        }
        if ("/data-ingestion-metrics".equals(path)) {
            return "Read data ingestion metrics, API statistics, or access logs.";
        }
        if ("/data-service-metrics".equals(path)) {
            return "Read data service metrics, API statistics, or access logs.";
        }
        if ("/quality-rules".equals(path)) {
            return "Page quality rules.";
        }
        if ("/quality-tasks".equals(path)) {
            return "Page quality tasks.";
        }
        if ("/quality-task-runs".equals(path)) {
            return "Page quality task run records.";
        }
        if ("/quality-metrics".equals(path)) {
            return "Read quality metrics, asset risks, or quality issues.";
        }
        if ("/notifications".equals(path)) {
            return "Read notifications, notification snapshot, or unread count.";
        }
        if ("/alerts".equals(path)) {
            return "Read alert summary, rules, incidents, channels, or delivery records.";
        }
        if ("/system".equals(path)) {
            return "Page system users, registration requests, tenants, projects, members, worker bindings, or resource shares.";
        }
        if ("/script-environments".equals(path)) {
            return "Page script runtime environments.";
        }
        if ("/ops-center".equals(path)) {
            return "Read operation center overview, queue, worker, incident, or event data.";
        }
        return "Read " + label + " list or overview.";
    }

    private String getToolPurpose(String path, String label) {
        if ("/models".equals(path)) {
            return "Read model details, fields, preview data, or lineage.";
        }
        if ("/collection-task-runs".equals(path)
                || "/runs".equals(path)
                || "/quality-task-runs".equals(path)) {
            return "Read run detail or logs.";
        }
        if ("/data-development".equals(path)) {
            return "Read script details.";
        }
        if ("/data-services".equals(path)) {
            return "Read data service detail, WebService preview, or subscriptions.";
        }
        if ("/data-ingestion-services".equals(path)) {
            return "Read data ingestion service detail, WebService preview, or subscriptions.";
        }
        if ("/protocol-conversions".equals(path)) {
            return "Read protocol conversion service detail or subscriptions.";
        }
        if ("/quality-metrics".equals(path)) {
            return "Read quality issue or asset risk detail.";
        }
        return "Read " + label + " detail.";
    }

    private List<String> listOptionalValues(String path) {
        if ("/catalog".equals(path)) {
            return list("view", "role", "datasourceType", "typeCode", "protocolMode");
        }
        if ("/metadata".equals(path)) {
            return list("includeFields");
        }
        if ("/datasources".equals(path)) {
            return list("pageNo", "pageSize", "keyword");
        }
        if ("/models".equals(path)) {
            return list("keyword", "datasourceId", "datasourceType", "pageNo", "pageSize", "sortField", "sortOrder");
        }
        if ("/statistics".equals(path)) {
            return list("view", "datasourceId", "datasourceType", "targetScope", "targetMetaSchemaCode",
                    "targetFieldKey", "chartType", "groups", "statType", "topN", "days", "bucketConfig", "payload");
        }
        if ("/field-mapping-rules".equals(path)) {
            return list("keyword", "mappingType", "enabled", "pageNo", "pageSize");
        }
        if ("/collection-tasks".equals(path)) {
            return list("keyword", "name", "targetDatasource", "targetModel", "pageNo", "pageSize");
        }
        if ("/collection-task-runs".equals(path)) {
            return list("collectionTaskId", "status", "startTime", "endTime", "pageNo", "pageSize");
        }
        if ("/run-metrics".equals(path)) {
            return list("startTime", "endTime", "executionType", "status", "granularity", "topN");
        }
        if ("/data-development".equals(path)) {
            return list("view", "scriptType", "runtimeClusterId");
        }
        if ("/workflows".equals(path)) {
            return list("pageNo", "pageSize");
        }
        if ("/runs".equals(path)) {
            return list("collectionTaskId", "qualityTaskId", "workflowDefinitionId", "status", "startTime", "endTime", "pageNo", "pageSize");
        }
        if ("/data-services".equals(path)) {
            return list("keyword", "status", "serviceType", "pageNo", "pageSize");
        }
        if ("/data-ingestion-services".equals(path)) {
            return list("keyword", "status", "targetType", "pageNo", "pageSize");
        }
        if ("/protocol-conversions".equals(path)) {
            return list("keyword", "status", "pageNo", "pageSize");
        }
        if ("/data-ingestion-metrics".equals(path)
                || "/data-service-metrics".equals(path)) {
            return list("view", "startTime", "endTime", "status", "pageNo", "pageSize");
        }
        if ("/quality-rules".equals(path)) {
            return list("keyword", "ruleDimension", "scopeType", "enabled", "pageNo", "pageSize");
        }
        if ("/quality-tasks".equals(path)) {
            return list("keyword", "status", "ruleDimension", "granularity", "pageNo", "pageSize");
        }
        if ("/quality-task-runs".equals(path)) {
            return list("qualityTaskId", "status", "startTime", "endTime", "pageNo", "pageSize");
        }
        if ("/quality-metrics".equals(path)) {
            return list("view", "datasourceId", "modelId", "keyword", "status", "severity", "ruleDimension", "granularity", "assigneeUserId", "startTime", "endTime", "topN", "pageNo", "pageSize");
        }
        if ("/notifications".equals(path)) {
            return list("view", "unreadOnly", "pageNo", "pageSize");
        }
        if ("/alerts".equals(path)) {
            return list("view", "keyword", "status", "severity", "ruleType", "subjectType", "enabled", "incidentId", "eventId", "channelId", "pageNo", "pageSize");
        }
        if ("/system".equals(path)) {
            return list("resource", "view", "tab", "projectId", "resourceType", "pageNo", "pageSize");
        }
        if ("/script-environments".equals(path)) {
            return list("keyword", "enabled", "pageNo", "pageSize");
        }
        if ("/ops-center".equals(path)) {
            return list("view", "startTime", "endTime", "executionType", "status", "workerGroupCode",
                    "requestedClusterId", "actualClusterId", "pageNo", "pageSize");
        }
        return emptyList();
    }

    private List<String> getOptionalValues(String path) {
        if ("/metadata".equals(path)) {
            return list("id", "schemaId", "schemaCode");
        }
        if ("/datasources".equals(path)) {
            return list("view", "days", "limit", "runtimeClusterId");
        }
        if ("/models".equals(path)) {
            return list("view", "preview", "lineageLevel", "limit", "runtimeClusterId");
        }
        if ("/collection-task-runs".equals(path)
                || "/runs".equals(path)
                || "/quality-task-runs".equals(path)) {
            return list("view", "log", "pageNo", "pageSizeBytes");
        }
        if ("/data-services".equals(path)
                || "/data-ingestion-services".equals(path)
                || "/protocol-conversions".equals(path)) {
            return list("view");
        }
        if ("/quality-metrics".equals(path)) {
            return list("resource");
        }
        return emptyList();
    }

    private List<String> getRequiredValues(String path) {
        if ("/metadata".equals(path)) {
            return list("path");
        }
        return list("path", "id");
    }

    private Map<String, Object> listDefaultParams(String path) {
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        List<String> optionalValues = listOptionalValues(path);
        if (optionalValues.contains("pageNo")) {
            defaults.put("pageNo", Integer.valueOf(1));
        }
        if (optionalValues.contains("pageSize")) {
            defaults.put("pageSize", Integer.valueOf(20));
        }
        return defaults;
    }

    private List<Map<String, Object>> defaultFrontendActions(String path, boolean supportsList) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        result.add(frontendAction("navigate", "studio.navigation.open", path));
        if (supportsList) {
            result.add(frontendAction("read", "studio.feature.list", path));
        }
        return result;
    }

    private List<Map<String, Object>> featureActions(String path) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if ("/access-center".equals(path)) {
            result.add(action(path, "apply", null, "申请进入项目或工作区。", true,
                    list("payload"), emptyList(), list("requestAccess")));
        } else if ("/metadata".equals(path)) {
            result.add(action(path, "saveDraft", null, "保存元模型草稿。", true, list("payload"), emptyList(), list("save")));
            result.add(action(path, "publish", null, "发布元模型。", true, list("id"), emptyList(), emptyList()));
            result.add(action(path, "syncTechnical", null, "同步指定数据源类型的技术元模型。", true, list("typeCode"), emptyList(), list("sync")));
            result.add(action(path, "syncAllTechnical", null, "同步全部技术元模型。", true, emptyList(), emptyList(), list("syncAll")));
            result.add(action(path, "syncStandardRuntimeOptions", null, "同步标准运行参数元模型。", true, emptyList(), emptyList(), list("syncRuntimeOptions")));
        } else if ("/datasources".equals(path)) {
            result.add(action(path, "save", null, "新增或修改数据源。", true,
                    list("payload", "payload.applicableClusterIds"), emptyList(), emptyList()));
            result.add(action(path, "test", null, "在指定运行集群测试已保存数据源连接。", true,
                    list("id", "runtimeClusterId"), emptyList(), emptyList()));
            result.add(action(path, "testCurrent", null, "在指定运行集群测试尚未保存的数据源配置。", true,
                    list("payload", "payload.applicableClusterIds", "runtimeClusterId"), emptyList(), emptyList()));
            result.add(action(path, "discover", null, "在指定运行集群发现数据源下的真实物理表/视图候选，不等同于已登记模型列表。", false,
                    list("id", "runtimeClusterId"), list("keyword", "pageNo", "pageSize"), list("discoverTables", "listPhysicalTables")));
        } else if ("/models".equals(path)) {
            result.add(action(path, "save", null, "保存模型定义。", true, list("payload"), emptyList(), emptyList()));
            result.add(action(path, "sync", null, "在指定运行集群同步指定数据源的模型。", true,
                    list("datasourceId", "runtimeClusterId"), emptyList(), list("syncDatasource")));
            result.add(action(path, "syncSelected", null, "在指定运行集群按选择范围同步指定数据源的模型。", true,
                    list("datasourceId", "physicalLocators", "runtimeClusterId"), emptyList(), emptyList()));
            result.add(action(path, "rebuildIndex", null, "重建模型检索索引。", true, emptyList(), list("datasourceId"), emptyList()));
        } else if ("/field-mapping-rules".equals(path)) {
            result.add(action(path, "save", null, "保存字段映射规则。", true, list("payload"), emptyList(), emptyList()));
        } else if ("/collection-tasks".equals(path)) {
            result.add(action(path, "preview", null, "预览采集任务配置。", false,
                    list("payload", "payload.runtimeClusterId"), emptyList(), list("validatePreview")));
            result.add(action(path, "save", null, "保存采集任务草稿或配置。", true,
                    list("payload", "payload.runtimeClusterId"), emptyList(), emptyList()));
            result.add(action(path, "publish", null, "上线采集任务。", true, list("id"), emptyList(), list("online")));
            result.add(action(path, "trigger", null, "立即触发采集任务，可按项目授权覆盖运行集群。", true,
                    list("id"), list("runtimeClusterId"), list("run", "execute")));
            result.add(action(path, "schedule", null, "保存采集任务调度配置。", true, list("id", "payload"), emptyList(), emptyList()));
            result.add(action(path, "resetIncrementalCursor", null, "重置采集任务增量游标。", true, list("id"), list("sourceAlias", "incrColumn", "incrModel"), emptyList()));
        } else if ("/data-development".equals(path)) {
            result.add(action(path, "saveDirectory", "directories", "保存脚本目录。", true, list("payload"), emptyList(), list("save")));
            result.add(action(path, "moveDirectory", "directories", "移动脚本目录。", true, list("id", "payload"), emptyList(), list("move")));
            result.add(action(path, "saveScript", "scripts", "保存脚本。", true,
                    list("payload", "payload.runtimeClusterId"), emptyList(), list("save")));
            result.add(action(path, "moveScript", "scripts", "移动脚本。", true, list("id", "payload"), emptyList(), list("move")));
            result.add(action(path, "executeSql", "sql", "在指定运行集群执行 SQL。", true,
                    list("payload", "payload.runtimeClusterId"), emptyList(), list("execute", "run")));
            result.add(action(path, "executeScript", "scripts", "在指定运行集群执行未保存脚本内容。", true,
                    list("payload", "payload.runtimeClusterId"), emptyList(), list("executeContent")));
            result.add(action(path, "executeSavedScript", "scripts", "执行已保存脚本。", true, list("id"), list("payload"), list("executeSaved", "runSaved")));
        } else if ("/workflows".equals(path)) {
            result.add(action(path, "save", null, "保存工作流定义。", true,
                    list("payload", "payload.runtimeClusterId"), emptyList(), emptyList()));
            result.add(action(path, "publish", null, "发布工作流。", true, list("id"), emptyList(), emptyList()));
            result.add(action(path, "trigger", null, "立即触发工作流，可按项目授权覆盖运行集群。", true,
                    list("id"), list("runtimeClusterId"), list("run", "execute")));
            result.add(action(path, "schedule", null, "保存工作流调度配置。", true, list("id", "payload"), emptyList(), list("timing", "cron")));
        } else if ("/quality-rules".equals(path)) {
            result.add(action(path, "save", null, "保存质量规则。", true, list("payload"), emptyList(), emptyList()));
            result.add(action(path, "enable", null, "启用质量规则。", true, list("id"), emptyList(), emptyList()));
            result.add(action(path, "disable", null, "禁用质量规则。", true, list("id"), emptyList(), emptyList()));
            result.add(action(path, "parse", null, "解析质量规则参数。", false, list("payload"), emptyList(), emptyList()));
            result.add(action(path, "validate", null, "校验质量规则。", false, list("payload"), emptyList(), emptyList()));
        } else if ("/quality-tasks".equals(path)) {
            result.add(action(path, "preview", null, "预览质量任务配置。", false,
                    list("payload", "payload.runtimeClusterId"), emptyList(), emptyList()));
            result.add(action(path, "validate", null, "校验质量任务配置。", false,
                    list("payload", "payload.runtimeClusterId"), emptyList(), emptyList()));
            result.add(action(path, "save", null, "保存质量任务。", true,
                    list("payload", "payload.runtimeClusterId"), emptyList(), emptyList()));
            result.add(action(path, "publish", null, "上线质量任务。", true, list("id"), emptyList(), list("online")));
            result.add(action(path, "trigger", null, "立即触发质量任务，可按项目授权覆盖运行集群。", true,
                    list("id"), list("runtimeClusterId"), list("run", "execute")));
            result.add(action(path, "schedule", null, "保存质量任务调度配置。", true, list("id", "payload"), emptyList(), emptyList()));
        } else if ("/script-environments".equals(path)) {
            result.add(action(path, "save", null, "保存脚本运行环境。", true, list("payload"), emptyList(), list("saveOrUpdateCheck")));
            result.add(action(path, "enable", null, "启用脚本运行环境。", true, list("id"), emptyList(), emptyList()));
            result.add(action(path, "disable", null, "禁用脚本运行环境。", true, list("id"), emptyList(), emptyList()));
            result.add(action(path, "refresh", null, "刷新脚本运行环境。", true, list("id"), emptyList(), emptyList()));
        } else if ("/quality-metrics".equals(path)) {
            result.add(action(path, "assignIssue", "issues", "分派质量问题。", true, list("id"), list("payload"), list("assign")));
            result.add(action(path, "updateIssueStatus", "issues", "更新质量问题状态。", true, list("id", "payload"), emptyList(), list("status")));
            result.add(action(path, "updateIssueSeverity", "issues", "更新质量问题严重级别。", true, list("id", "payload"), emptyList(), list("severity")));
            result.add(action(path, "addIssueComment", "issues", "追加质量问题评论。", true, list("id", "payload"), emptyList(), list("comment")));
        } else if ("/notifications".equals(path)) {
            result.add(action(path, "markRead", null, "标记通知为已读。", true, list("id"), emptyList(), list("read")));
            result.add(action(path, "markAllRead", null, "标记全部通知为已读。", true, emptyList(), emptyList(), list("readAll")));
        } else if ("/alerts".equals(path)) {
            result.add(action(path, "enableRule", "rules", "启用告警规则。", true, list("id"), emptyList(), list("enable")));
            result.add(action(path, "disableRule", "rules", "停用告警规则。", true, list("id"), emptyList(), list("disable")));
            result.add(action(path, "testRule", "rules", "测试告警规则通知。", true, list("id"), emptyList(), list("test")));
            result.add(action(path, "acknowledgeIncident", "incidents", "确认告警事件。", true, list("id"), list("comment"), list("acknowledge")));
            result.add(action(path, "closeIncident", "incidents", "关闭告警事件。", true, list("id"), list("comment"), list("close")));
            result.add(action(path, "testChannel", "channels", "测试 Webhook 通道。", true, list("id"), emptyList(), list("testWebhook")));
            result.add(action(path, "retryDelivery", "deliveries", "重试失败的告警投递。", true, list("id"), emptyList(), list("retry")));
        } else if ("/system".equals(path)) {
            addSystemActions(result, path);
        } else if (isServicePath(path)) {
            addServiceActions(result, path);
        }
        return result;
    }

    private void addSystemActions(List<Map<String, Object>> result, String path) {
        result.add(action(path, "saveUser", "users", "保存系统用户。", true, list("payload"), emptyList(), list("save")));
        result.add(action(path, "deleteUser", "users", "删除系统用户。", true, list("id"), emptyList(), list("delete")));
        result.add(action(path, "approve", "userRegistrationRequests", "通过用户注册申请。", true, list("id"), list("payload"), emptyList()));
        result.add(action(path, "reject", "userRegistrationRequests", "拒绝用户注册申请。", true, list("id"), list("payload"), emptyList()));
        result.add(action(path, "deleteRegistrationRequest", "userRegistrationRequests", "删除用户注册申请。", true, list("id"), emptyList(), list("delete")));
        result.add(action(path, "approve", "registrationRequests", "通过用户注册申请。", true, list("id"), list("payload"), emptyList()));
        result.add(action(path, "reject", "registrationRequests", "拒绝用户注册申请。", true, list("id"), list("payload"), emptyList()));
        result.add(action(path, "deleteRegistrationRequest", "registrationRequests", "删除用户注册申请。", true, list("id"), emptyList(), list("delete")));
        result.add(action(path, "saveTenant", "tenants", "保存租户。", true, list("payload"), emptyList(), list("save")));
        result.add(action(path, "deleteTenant", "tenants", "删除租户。", true, list("id"), emptyList(), list("delete")));
        result.add(action(path, "saveProject", "projects", "保存项目。", true, list("payload"), emptyList(), list("save")));
        result.add(action(path, "deleteProject", "projects", "删除项目。", true, list("id"), emptyList(), list("delete")));
        result.add(action(path, "saveTenantMember", "tenantMembers", "保存租户成员。", true, list("payload"), emptyList(), list("save")));
        result.add(action(path, "deleteTenantMember", "tenantMembers", "删除租户成员。", true, list("id"), emptyList(), list("delete")));
        result.add(action(path, "saveProjectMember", "projectMembers", "保存项目成员。", true, list("payload"), emptyList(), list("save")));
        result.add(action(path, "deleteProjectMember", "projectMembers", "删除项目成员。", true, list("id"), emptyList(), list("delete")));
        result.add(action(path, "saveProjectMemberRequest", "projectMemberRequests", "保存项目成员申请。", true, list("payload"), emptyList(), list("save")));
        result.add(action(path, "approve", "projectMemberRequests", "通过项目成员申请。", true, list("id", "payload"), emptyList(), emptyList()));
        result.add(action(path, "reject", "projectMemberRequests", "拒绝项目成员申请。", true, list("id", "payload"), emptyList(), emptyList()));
        result.add(action(path, "deleteProjectMemberRequest", "projectMemberRequests", "删除项目成员申请。", true, list("id"), emptyList(), list("delete")));
        result.add(action(path, "saveProjectMemberRequest", "requests", "保存项目成员申请。", true, list("payload"), emptyList(), list("save")));
        result.add(action(path, "approve", "requests", "通过项目成员申请。", true, list("id", "payload"), emptyList(), emptyList()));
        result.add(action(path, "reject", "requests", "拒绝项目成员申请。", true, list("id", "payload"), emptyList(), emptyList()));
        result.add(action(path, "deleteProjectMemberRequest", "requests", "删除项目成员申请。", true, list("id"), emptyList(), list("delete")));
        result.add(action(path, "saveProjectWorker", "projectWorkers", "保存项目 Worker 绑定。", true, list("payload"), emptyList(), list("save")));
        result.add(action(path, "deleteProjectWorker", "projectWorkers", "删除项目 Worker 绑定。", true, list("id"), emptyList(), list("delete")));
        result.add(action(path, "saveProjectWorker", "workers", "保存项目 Worker 绑定。", true, list("payload"), emptyList(), list("save")));
        result.add(action(path, "deleteProjectWorker", "workers", "删除项目 Worker 绑定。", true, list("id"), emptyList(), list("delete")));
        result.add(action(path, "saveResourceShare", "resourceShares", "保存资源共享。", true, list("payload"), emptyList(), list("save")));
        result.add(action(path, "deleteResourceShare", "resourceShares", "删除资源共享。", true, list("id"), emptyList(), list("delete")));
        result.add(action(path, "saveResourceShare", "shares", "保存资源共享。", true, list("payload"), emptyList(), list("save")));
        result.add(action(path, "deleteResourceShare", "shares", "删除资源共享。", true, list("id"), emptyList(), list("delete")));
    }

    private void addServiceActions(List<Map<String, Object>> result, String path) {
        String serviceName = "/data-ingestion-services".equals(path) ? "数据接入服务"
                : "/protocol-conversions".equals(path) ? "协议转换服务" : "数据服务";
        result.add(action(path, "save", null, "保存" + serviceName + "。", true,
                list("payload", "payload.runtimeClusterId"), emptyList(), emptyList()));
        result.add(action(path, "publish", null, "发布" + serviceName + "。", true, list("id"), emptyList(), emptyList()));
        result.add(action(path, "offline", null, "下线" + serviceName + "。", true, list("id"), emptyList(), emptyList()));
        result.add(action(path, "debug", null, "调试" + serviceName + "。", true, list("id", "payload"), emptyList(), emptyList()));
        if (!"/protocol-conversions".equals(path)) {
            result.add(action(path, "resolveFields", null, "解析" + serviceName + "字段。", false,
                    list("payload", "payload.runtimeClusterId"), emptyList(), emptyList()));
            result.add(action(path, "debugWebService", "webservice", "调试" + serviceName + " WebService。", true, list("id", "payload"), emptyList(), list("debug")));
        }
        result.add(action(path, "createSubscription", "subscriptions", "创建" + serviceName + "订阅。", true, list("id", "subscriptionName"), emptyList(), list("subscribe")));
        result.add(action(path, "enableSubscription", "subscriptions", "启用" + serviceName + "订阅。", true, list("id", "subscriptionId"), emptyList(), list("enable")));
        result.add(action(path, "disableSubscription", "subscriptions", "禁用" + serviceName + "订阅。", true, list("id", "subscriptionId"), emptyList(), list("disable")));
        result.add(action(path, "rotateSubscription", "subscriptions", "轮换" + serviceName + "订阅密钥。", true, list("id", "subscriptionId"), emptyList(), list("rotate")));
    }

    private boolean isServicePath(String path) {
        return "/data-services".equals(path)
                || "/data-ingestion-services".equals(path)
                || "/protocol-conversions".equals(path);
    }

    private Map<String, Object> action(String path,
                                       String action,
                                       String resource,
                                       String purpose,
                                       boolean mutation,
                                       List<String> requiredValues,
                                       List<String> optionalValues,
                                       List<String> aliases) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("path", path);
        item.put("action", action);
        if (resource != null && !resource.trim().isEmpty()) {
            item.put("resource", resource);
        }
        item.put("purpose", purpose);
        item.put("mutation", Boolean.valueOf(mutation));
        item.put("requiredValues", requiredValues);
        item.put("optionalValues", optionalValues);
        item.put("aliases", aliases);
        item.put("type", "frontendTool");
        item.put("tool", "studio.feature.action");
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("path", path);
        params.put("action", action);
        if (resource != null && !resource.trim().isEmpty()) {
            params.put("resource", resource);
        }
        item.put("params", params);
        return item;
    }

    private List<String> list(String... values) {
        return Arrays.asList(values);
    }

    private List<String> emptyList() {
        return Collections.emptyList();
    }

    private Map<String, Object> emptyMap() {
        return Collections.emptyMap();
    }

    private Map<String, Object> frontendAction(String intent, String tool, String path) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("intent", intent);
        item.put("type", "frontendTool");
        item.put("tool", tool);
        item.put("reason", intent + " " + path);
        item.put("params", Collections.singletonMap("path", path));
        return item;
    }

    private boolean matches(Map<String, Object> operation, String query) {
        String text = searchableText(operation);
        for (String token : tokens(query)) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String searchableText(Map<String, Object> operation) {
        return (stringValue(operation.get("label")) + "\n"
                + stringValue(operation.get("capabilityCode")) + "\n"
                + stringValue(operation.get("group")) + "\n"
                + stringValue(operation.get("path")) + "\n"
                + stringValue(operation.get("description")) + "\n"
                + stringValue(operation.get("tags"))).toLowerCase(Locale.ROOT);
    }

    private boolean isIntentToken(String token) {
        return "open".equals(token)
                || "navigate".equals(token)
                || "show".equals(token)
                || "list".equals(token)
                || "read".equals(token)
                || "query".equals(token)
                || "查看".equals(token)
                || "打开".equals(token)
                || "进入".equals(token)
                || "读取".equals(token)
                || "查询".equals(token);
    }

    private Set<String> tokens(String query) {
        Set<String> result = new LinkedHashSet<String>();
        String normalized = stringValue(query)
                .replaceAll("[^\\p{IsHan}A-Za-z0-9_/-]+", " ")
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

    private List<String> tags(String... values) {
        return Arrays.asList(values);
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

    private String normalizePath(String path) {
        String trimmed = path == null ? "" : path.trim();
        if (!StringUtils.hasText(trimmed)) {
            return "";
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number) {
            return Math.max(1, Math.min(50, ((Number) value).intValue()));
        }
        try {
            return Math.max(1, Math.min(50, Integer.parseInt(stringValue(value))));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
