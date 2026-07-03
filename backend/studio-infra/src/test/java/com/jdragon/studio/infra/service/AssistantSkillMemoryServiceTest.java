package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.assistant.AssistantPlanRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantSkillMemoryServiceTest {

    @TempDir
    private Path tempDir;

    @Test
    void learnedSkillsShouldBeSavedSyncedAndLoadedBeforeBuiltinSkills() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getAssistant().getSkillMemory().setLocalDir(tempDir.toString());
        properties.getAssistant().getSkillMemory().setObjectPrefix("/custom/assistant-skills/");
        properties.getAssistant().getSkillMemory().setMaxContextSkills(3);

        RecordingCloudObjectStorageService objectStorage = new RecordingCloudObjectStorageService(properties);
        AssistantSkillMemoryService service = new AssistantSkillMemoryService(
                properties,
                objectStorage,
                new ObjectMapper());

        AssistantPlanRequest request = new AssistantPlanRequest();
        request.setMessage("Studio 采集任务字段映射应该怎么确认");
        request.getContext().put("tenantId", "tenant-a");
        request.getContext().put("projectId", "project-a");

        boolean accepted = service.rememberInteraction(request,
                "Studio 采集任务字段映射需要先读取源模型和目标模型字段，自动匹配同名字段；"
                        + "未匹配字段必须通过问答控件让用户确认，不能自动丢弃，也不能保存空映射。"
                        + "保存前还要调用 preview 接口生成预览。");

        assertTrue(accepted);
        assertTrue(Files.exists(tempDir.resolve("index.json")));
        assertTrue(objectStorage.keys.contains("custom/assistant-skills/index.json"));
        assertTrue(objectStorage.keys.stream().anyMatch(key ->
                key.startsWith("custom/assistant-skills/learned-") && key.endsWith(".json")));

        List<Map<String, Object>> skills = service.loadRelevantSkills(request);
        assertFalse(skills.isEmpty());
        assertEquals("learned", skills.get(0).get("type"));
        assertTrue(String.valueOf(skills.get(0).get("content")).contains("未匹配字段必须通过问答控件"));
    }

    private static class RecordingCloudObjectStorageService extends CloudObjectStorageService {
        private final List<String> keys = new ArrayList<String>();
        private final Map<String, byte[]> objects = new LinkedHashMap<String, byte[]>();

        RecordingCloudObjectStorageService(StudioPlatformProperties properties) {
            super(properties);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public boolean bucketConfigured() {
            return true;
        }

        @Override
        public String resolveBucket() {
            return "assistant-test";
        }

        @Override
        public void put(String bucket, String objectKey, byte[] bytes, String contentType) {
            keys.add(objectKey);
            objects.put(objectKey, bytes);
        }

        @Override
        public byte[] get(String bucket, String objectKey) {
            return objects.get(objectKey);
        }
    }
}
