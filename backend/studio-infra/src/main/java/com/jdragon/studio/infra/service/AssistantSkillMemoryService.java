package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.assistant.AssistantPlanRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class AssistantSkillMemoryService {

    private static final String INDEX_FILE = "index.json";
    private static final int MAX_SKILL_CONTENT_CHARS = 1200;
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<List<Map<String, Object>>>() {
    };

    private final StudioPlatformProperties properties;
    private final CloudObjectStorageService cloudObjectStorageService;
    private final ObjectMapper objectMapper;

    public AssistantSkillMemoryService(StudioPlatformProperties properties,
                                       CloudObjectStorageService cloudObjectStorageService,
                                       ObjectMapper objectMapper) {
        this.properties = properties;
        this.cloudObjectStorageService = cloudObjectStorageService;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> loadRelevantSkills(AssistantPlanRequest request) {
        if (!properties.getAssistant().getSkillMemory().isEnabled()) {
            return defaultSkills();
        }
        String query = latestMessage(request);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        result.addAll(loadLearnedSkills(query));
        result.addAll(defaultSkills());
        int max = Math.max(1, properties.getAssistant().getSkillMemory().getMaxContextSkills());
        if (result.size() > max) {
            return result.subList(0, max);
        }
        return result;
    }

    @AssistantBackendTool(
            code = "assistant.skills.search",
            name = "搜索助手技能记忆",
            description = "按当前问题检索 Studio 助手已沉淀的技能摘要，只返回文本知识，不执行业务写操作。"
    )
    public List<Map<String, Object>> searchSkills(AssistantPlanRequest request, Map<String, Object> params) {
        String query = stringValue(params.get("query"));
        if (!StringUtils.hasText(query)) {
            query = latestMessage(request);
        }
        return loadRelevantSkills(withMessage(request, query));
    }

    public boolean rememberInteraction(AssistantPlanRequest request, String assistantContent) {
        if (!properties.getAssistant().getSkillMemory().isEnabled()) {
            return false;
        }
        String message = latestMessage(request);
        if (!isConstructive(message, assistantContent)) {
            return false;
        }
        Map<String, Object> skill = new LinkedHashMap<String, Object>();
        String id = "learned-" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        skill.put("id", id);
        skill.put("type", "learned");
        skill.put("title", titleFor(message));
        skill.put("tags", tagsFor(message + "\n" + assistantContent));
        skill.put("sourceQuestion", sanitize(message, 500));
        skill.put("content", sanitize(assistantContent, MAX_SKILL_CONTENT_CHARS));
        skill.put("createdAt", Instant.now().toString());
        Map<String, Object> context = request == null ? Collections.<String, Object>emptyMap() : request.getContext();
        skill.put("tenantId", context.get("tenantId"));
        skill.put("projectId", context.get("projectId"));
        return saveSkill(skill);
    }

    private boolean saveSkill(Map<String, Object> skill) {
        try {
            Files.createDirectories(skillDir());
            String id = stringValue(skill.get("id"));
            Path file = skillDir().resolve(id + ".json");
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(skill);
            Files.write(file, bytes);
            syncLocalIndex();
            syncToObjectStorage(id + ".json", bytes);
            return true;
        } catch (Exception ignored) {
            // Skill memory must never block the assistant response path.
            return false;
        }
    }

    private void syncLocalIndex() throws Exception {
        List<Map<String, Object>> index = new ArrayList<Map<String, Object>>();
        if (Files.isDirectory(skillDir())) {
            try (Stream<Path> paths = Files.list(skillDir())) {
                paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .filter(path -> !INDEX_FILE.equals(path.getFileName().toString()))
                        .forEach(path -> {
                            Map<String, Object> item = new LinkedHashMap<String, Object>();
                            item.put("file", path.getFileName().toString());
                            index.add(item);
                        });
            }
        }
        byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(index);
        Files.write(skillDir().resolve(INDEX_FILE), bytes);
        syncToObjectStorage(INDEX_FILE, bytes);
    }

    private void syncToObjectStorage(String filename, byte[] bytes) {
        try {
            if (!cloudObjectStorageService.available()) {
                return;
            }
            cloudObjectStorageService.put(
                    cloudObjectStorageService.resolveBucket(),
                    objectPrefix() + "/" + filename,
                    bytes,
                    "application/json");
        } catch (Exception ignored) {
            // Local skill files remain the source of truth when object storage is unavailable.
        }
    }

    private List<Map<String, Object>> loadLearnedSkills(String query) {
        List<Map<String, Object>> local = loadLocalSkills(query);
        if (!local.isEmpty()) {
            return local;
        }
        return loadObjectStorageSkills(query);
    }

    private List<Map<String, Object>> loadLocalSkills(String query) {
        try {
            if (!Files.isDirectory(skillDir())) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
            try (Stream<Path> paths = Files.list(skillDir())) {
                paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .filter(path -> !INDEX_FILE.equals(path.getFileName().toString()))
                        .forEach(path -> readSkillFile(path, query, result));
            }
            return result;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> loadObjectStorageSkills(String query) {
        try {
            if (!cloudObjectStorageService.bucketConfigured()) {
                return Collections.emptyList();
            }
            String bucket = cloudObjectStorageService.resolveBucket();
            byte[] indexBytes = cloudObjectStorageService.get(bucket, objectPrefix() + "/" + INDEX_FILE);
            List<Map<String, Object>> index = objectMapper.readValue(indexBytes, LIST_OF_MAPS);
            List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> item : index) {
                String file = stringValue(item.get("file"));
                if (!StringUtils.hasText(file)) {
                    continue;
                }
                byte[] bytes = cloudObjectStorageService.get(bucket, objectPrefix() + "/" + file);
                Map<String, Object> skill = objectMapper.readValue(bytes, new TypeReference<Map<String, Object>>() {
                });
                if (matches(skill, query)) {
                    result.add(toContextSkill(skill));
                }
            }
            return result;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private void readSkillFile(Path path, String query, List<Map<String, Object>> result) {
        try {
            Map<String, Object> skill = objectMapper.readValue(Files.readAllBytes(path), new TypeReference<Map<String, Object>>() {
            });
            if (matches(skill, query)) {
                result.add(toContextSkill(skill));
            }
        } catch (Exception ignored) {
            // Ignore corrupt skill files.
        }
    }

    private Map<String, Object> toContextSkill(Map<String, Object> skill) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("id", skill.get("id"));
        summary.put("type", skill.get("type"));
        summary.put("title", skill.get("title"));
        summary.put("tags", skill.get("tags"));
        summary.put("content", sanitize(stringValue(skill.get("content")), MAX_SKILL_CONTENT_CHARS));
        return summary;
    }

    private boolean matches(Map<String, Object> skill, String query) {
        if (!StringUtils.hasText(query)) {
            return true;
        }
        String text = (stringValue(skill.get("title")) + "\n" + stringValue(skill.get("tags")) + "\n" + stringValue(skill.get("content"))).toLowerCase(Locale.ROOT);
        for (String token : tokens(query)) {
            if (text.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> defaultSkills() {
        List<Map<String, Object>> skills = new ArrayList<Map<String, Object>>();
        skills.add(defaultSkill("studio-overview", "Studio 模块地图", "overview",
                "Studio 覆盖数据源、模型中心、采集任务、工作流、数据开发、数据服务、协议转换、数据质量和运维中心。普通问答可以解释概念；只有明确创建/配置/生成任务时才打开表单。"));
        skills.add(defaultSkill("collection-single-table", "单表采集创建技能", "collection,single-table",
                "创建单表采集时先识别任务名、源数据源、源模型、目标数据源、目标模型和字段映射。只通过 Studio HTTP 接口查询候选；保存前必须 preview；默认 FULL；不生成定时调度。"));
        skills.add(defaultSkill("datasource-and-model-resolution", "数据源与模型候选解析", "datasource,model,candidate",
                "数据源候选来自 /api/v1/datasources/options，模型候选来自 /api/v1/models/datasource/{datasourceId}/options，字段元数据来自 /api/v1/models/{modelId}。当名称重复、匹配分数接近或缺少权限时，必须让用户选择，不要猜 ID。"));
        skills.add(defaultSkill("collection-preview-confirmation", "采集任务确认边界", "collection,preview,confirmation",
                "采集任务保存前必须先调用 /api/v1/collection-tasks/preview 生成 JobContainer 预览。保存、上线、立即触发、删除和修改配置都必须二次确认；助手计划接口只返回计划和候选，不执行业务写操作。"));
        skills.add(defaultSkill("assistant-progress-style", "助手过程展示", "assistant,progress,ui",
                "助手应展示低注意力的过程事件，例如整理上下文、加载知识、调用接口、扫描候选、读取字段、生成映射和预览配置。展示的是系统动作摘要，不展示模型隐藏推理。"));
        skills.add(defaultSkill("assistant-boundaries", "助手边界", "safety,boundary",
                "助手不能直接访问底层 DataAggregation 代码或数据库，不能绕过用户确认执行保存、发布、触发、删除和修改配置。后端反射工具只允许调用带 AssistantBackendTool 注解的白名单方法。"));
        return skills;
    }

    private Map<String, Object> defaultSkill(String id, String title, String tags, String content) {
        Map<String, Object> skill = new LinkedHashMap<String, Object>();
        skill.put("id", id);
        skill.put("type", "builtin");
        skill.put("title", title);
        skill.put("tags", tags);
        skill.put("content", content);
        return skill;
    }

    private boolean isConstructive(String message, String content) {
        if (!StringUtils.hasText(message) || !StringUtils.hasText(content) || content.length() < 80) {
            return false;
        }
        String text = (message + "\n" + content).toLowerCase(Locale.ROOT);
        return text.contains("studio")
                || text.contains("数据源")
                || text.contains("模型")
                || text.contains("采集")
                || text.contains("工作流")
                || text.contains("字段")
                || text.contains("接口")
                || text.contains("任务");
    }

    private String titleFor(String message) {
        String text = sanitize(message, 48);
        return StringUtils.hasText(text) ? text : "Studio 助手经验";
    }

    private Set<String> tagsFor(String text) {
        Set<String> tags = new LinkedHashSet<String>();
        String value = text == null ? "" : text;
        addTagIfContains(tags, value, "数据源", "datasource");
        addTagIfContains(tags, value, "模型", "model");
        addTagIfContains(tags, value, "采集", "collection");
        addTagIfContains(tags, value, "工作流", "workflow");
        addTagIfContains(tags, value, "字段", "field-mapping");
        addTagIfContains(tags, value, "服务", "service");
        if (tags.isEmpty()) {
            tags.add("studio");
        }
        return tags;
    }

    private void addTagIfContains(Set<String> tags, String text, String needle, String tag) {
        if (text.contains(needle)) {
            tags.add(tag);
        }
    }

    private List<String> tokens(String query) {
        List<String> result = new ArrayList<String>();
        String normalized = query == null ? "" : query.replaceAll("[^\\p{IsHan}A-Za-z0-9_]+", " ").trim();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                result.add(token);
            }
        }
        if (result.isEmpty() && StringUtils.hasText(query)) {
            result.add(query.trim());
        }
        return result;
    }

    private AssistantPlanRequest withMessage(AssistantPlanRequest request, String message) {
        AssistantPlanRequest copy = new AssistantPlanRequest();
        if (request != null) {
            copy.setMessages(request.getMessages());
            copy.setContext(request.getContext());
            copy.setCollectedInputs(request.getCollectedInputs());
            copy.setToolResults(request.getToolResults());
        }
        copy.setMessage(message);
        return copy;
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
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            if (request.getMessages().get(i) != null && StringUtils.hasText(request.getMessages().get(i).getContent())) {
                return request.getMessages().get(i).getContent().trim();
            }
        }
        return "";
    }

    private String sanitize(String value, int maxChars) {
        String text = stringValue(value)
                .replaceAll("sk-[A-Za-z0-9_-]{12,}", "sk-***")
                .replaceAll("Bearer\\s+[A-Za-z0-9._-]+", "Bearer ***")
                .trim();
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Path skillDir() {
        String configured = properties.getAssistant().getSkillMemory().getLocalDir();
        if (StringUtils.hasText(configured)) {
            return Paths.get(configured);
        }
        return Paths.get("runtime", "assistant-skills");
    }

    private String objectPrefix() {
        String prefix = properties.getAssistant().getSkillMemory().getObjectPrefix();
        return StringUtils.hasText(prefix) ? trimSlashes(prefix) : "studio/assistant-skills";
    }

    private String trimSlashes(String value) {
        String result = value == null ? "" : value.replace('\\', '/');
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
