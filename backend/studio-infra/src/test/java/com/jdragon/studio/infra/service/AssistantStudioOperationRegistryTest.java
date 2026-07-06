package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.assistant.AssistantPlanRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantStudioOperationRegistryTest {

    @Test
    void operationSkillsShouldExposePortableFeatureCatalog() {
        AssistantStudioOperationRegistry registry = new AssistantStudioOperationRegistry();
        AssistantPlanRequest request = new AssistantPlanRequest();
        request.setMessage("data development sql");

        List<Map<String, Object>> skills = registry.assistantSkills(request);

        assertFalse(skills.isEmpty());
        Map<String, Object> skill = skills.stream()
                .filter(item -> "/data-development".equals(item.get("path")))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals("studio.skill.v1", skill.get("schema"));
        assertEquals(Boolean.TRUE, skill.get("portable"));
        assertEquals("studio.operation.catalog", skill.get("kind"));
        assertEquals("/data-development", skill.get("path"));
        assertTrue(String.valueOf(skill.get("content")).contains("studio.feature.list"));
        @SuppressWarnings("unchecked")
        Map<String, Object> operation = (Map<String, Object>) skill.get("operation");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readTools = (List<Map<String, Object>>) operation.get("readTools");
        Map<String, Object> listTool = readTools.stream()
                .filter(item -> "studio.feature.list".equals(item.get("tool")))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals("frontendTool", listTool.get("type"));
        assertEquals(Boolean.FALSE, listTool.get("mutation"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("view"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("scriptType"));
        Map<String, Object> getTool = readTools.stream()
                .filter(item -> "studio.feature.get".equals(item.get("tool")))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertTrue(((List<?>) getTool.get("requiredValues")).contains("id"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> featureActions = (List<Map<String, Object>>) operation.get("featureActions");
        Map<String, Object> executeSqlAction = featureActions.stream()
                .filter(item -> "executeSql".equals(item.get("action")))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals("studio.feature.action", executeSqlAction.get("tool"));
        assertTrue(((List<?>) executeSqlAction.get("requiredValues")).contains("payload"));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) executeSqlAction.get("params");
        assertEquals("/data-development", params.get("path"));
        assertEquals("executeSql", params.get("action"));
    }

    @Test
    void readToolsShouldDescribeBackendCatalogListParameters() {
        AssistantStudioOperationRegistry registry = new AssistantStudioOperationRegistry();

        Map<String, Object> datasourceOperation = registry.allOperations().stream()
                .filter(item -> "/datasources".equals(item.get("path")))
                .findFirst()
                .orElseThrow(AssertionError::new);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readTools = (List<Map<String, Object>>) datasourceOperation.get("readTools");
        Map<String, Object> listTool = readTools.stream()
                .filter(item -> "studio.feature.list".equals(item.get("tool")))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals("frontendTool", listTool.get("type"));
        assertEquals(Boolean.FALSE, listTool.get("mutation"));
        assertEquals("Page datasource connections.", listTool.get("purpose"));
        assertTrue(((List<?>) listTool.get("requiredValues")).contains("path"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("keyword"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("pageNo"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("pageSize"));
        @SuppressWarnings("unchecked")
        Map<String, Object> defaultParams = (Map<String, Object>) listTool.get("defaultParams");
        assertEquals("/datasources", defaultParams.get("path"));
        assertEquals(1, defaultParams.get("pageNo"));
        assertEquals(20, defaultParams.get("pageSize"));
    }

    @Test
    void readToolsShouldDescribeQualityMetricsFilters() {
        AssistantStudioOperationRegistry registry = new AssistantStudioOperationRegistry();

        Map<String, Object> qualityMetricsOperation = registry.allOperations().stream()
                .filter(item -> "/quality-metrics".equals(item.get("path")))
                .findFirst()
                .orElseThrow(AssertionError::new);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readTools = (List<Map<String, Object>>) qualityMetricsOperation.get("readTools");
        Map<String, Object> listTool = readTools.stream()
                .filter(item -> "studio.feature.list".equals(item.get("tool")))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("severity"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("modelId"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("topN"));
    }

    @Test
    void readToolsShouldDescribeStatisticsViews() {
        AssistantStudioOperationRegistry registry = new AssistantStudioOperationRegistry();

        Map<String, Object> statisticsOperation = registry.allOperations().stream()
                .filter(item -> "/statistics".equals(item.get("path")))
                .findFirst()
                .orElseThrow(AssertionError::new);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readTools = (List<Map<String, Object>>) statisticsOperation.get("readTools");
        Map<String, Object> listTool = readTools.stream()
                .filter(item -> "studio.feature.list".equals(item.get("tool")))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("view"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("datasourceType"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("targetMetaSchemaCode"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("targetFieldKey"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("chartType"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("groups"));
    }

    @Test
    void readToolsShouldDescribeCatalogRuntimeOptionSchemaView() {
        AssistantStudioOperationRegistry registry = new AssistantStudioOperationRegistry();

        Map<String, Object> catalogOperation = registry.allOperations().stream()
                .filter(item -> "/catalog".equals(item.get("path")))
                .findFirst()
                .orElseThrow(AssertionError::new);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readTools = (List<Map<String, Object>>) catalogOperation.get("readTools");
        Map<String, Object> listTool = readTools.stream()
                .filter(item -> "studio.feature.list".equals(item.get("tool")))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("view"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("role"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("datasourceType"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("protocolMode"));
    }

    @Test
    void readToolsShouldDescribeNotificationsAndMarkReadActions() {
        AssistantStudioOperationRegistry registry = new AssistantStudioOperationRegistry();

        Map<String, Object> notificationsOperation = registry.allOperations().stream()
                .filter(item -> "/notifications".equals(item.get("path")))
                .findFirst()
                .orElseThrow(AssertionError::new);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readTools = (List<Map<String, Object>>) notificationsOperation.get("readTools");
        Map<String, Object> listTool = readTools.stream()
                .filter(item -> "studio.feature.list".equals(item.get("tool")))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("view"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("unreadOnly"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> featureActions = (List<Map<String, Object>>) notificationsOperation.get("featureActions");
        Map<String, Object> markRead = featureActions.stream()
                .filter(item -> "markRead".equals(item.get("action")))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals(Boolean.TRUE, markRead.get("mutation"));
        assertTrue(((List<?>) markRead.get("requiredValues")).contains("id"));
    }

    @Test
    void operationSkillsShouldMatchChineseNotificationMutationIntent() {
        AssistantStudioOperationRegistry registry = new AssistantStudioOperationRegistry();
        AssistantPlanRequest request = new AssistantPlanRequest();
        request.setMessage("请把所有通知全部标记为已读，必须通过 Studio 助手确认后执行。");

        List<Map<String, Object>> skills = registry.assistantSkills(request);

        Map<String, Object> skill = skills.stream()
                .filter(item -> "/notifications".equals(item.get("path")))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals("studio.operation.catalog", skill.get("kind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> operation = (Map<String, Object>) skill.get("operation");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> featureActions = (List<Map<String, Object>>) operation.get("featureActions");
        assertTrue(featureActions.stream().anyMatch(item -> "markAllRead".equals(item.get("action"))));
    }

    @Test
    void readToolsShouldDescribeMetadataSchemaCodeLookup() {
        AssistantStudioOperationRegistry registry = new AssistantStudioOperationRegistry();

        Map<String, Object> metadataOperation = registry.allOperations().stream()
                .filter(item -> "/metadata".equals(item.get("path")))
                .findFirst()
                .orElseThrow(AssertionError::new);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readTools = (List<Map<String, Object>>) metadataOperation.get("readTools");
        Map<String, Object> getTool = readTools.stream()
                .filter(item -> "studio.feature.get".equals(item.get("tool")))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertTrue(((List<?>) getTool.get("requiredValues")).contains("path"));
        assertFalse(((List<?>) getTool.get("requiredValues")).contains("id"));
        assertTrue(((List<?>) getTool.get("optionalValues")).contains("id"));
        assertTrue(((List<?>) getTool.get("optionalValues")).contains("schemaId"));
        assertTrue(((List<?>) getTool.get("optionalValues")).contains("schemaCode"));
    }

    @Test
    void readToolsShouldDescribeSystemResourceSelector() {
        AssistantStudioOperationRegistry registry = new AssistantStudioOperationRegistry();

        Map<String, Object> systemOperation = registry.allOperations().stream()
                .filter(item -> "/system".equals(item.get("path")))
                .findFirst()
                .orElseThrow(AssertionError::new);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readTools = (List<Map<String, Object>>) systemOperation.get("readTools");
        Map<String, Object> listTool = readTools.stream()
                .filter(item -> "studio.feature.list".equals(item.get("tool")))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("resource"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("view"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("tab"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("projectId"));
        assertTrue(((List<?>) listTool.get("optionalValues")).contains("resourceType"));
    }

    @Test
    void featureActionsShouldDescribeSystemMutations() {
        AssistantStudioOperationRegistry registry = new AssistantStudioOperationRegistry();

        Map<String, Object> systemOperation = registry.allOperations().stream()
                .filter(item -> "/system".equals(item.get("path")))
                .findFirst()
                .orElseThrow(AssertionError::new);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> featureActions = (List<Map<String, Object>>) systemOperation.get("featureActions");
        Map<String, Object> approveRegistration = featureActions.stream()
                .filter(item -> "approve".equals(item.get("action")))
                .filter(item -> "userRegistrationRequests".equals(item.get("resource")))
                .findFirst()
                .orElseThrow(AssertionError::new);
        Map<String, Object> deleteUser = featureActions.stream()
                .filter(item -> "deleteUser".equals(item.get("action")))
                .filter(item -> "users".equals(item.get("resource")))
                .findFirst()
                .orElseThrow(AssertionError::new);

        assertEquals("studio.feature.action", approveRegistration.get("tool"));
        assertEquals(Boolean.TRUE, approveRegistration.get("mutation"));
        assertTrue(((List<?>) approveRegistration.get("requiredValues")).contains("id"));
        assertEquals(Boolean.TRUE, deleteUser.get("mutation"));
        assertTrue(((List<?>) deleteUser.get("requiredValues")).contains("id"));
    }

    @Test
    void featureActionsShouldDescribeModelSyncSelectedLocators() {
        AssistantStudioOperationRegistry registry = new AssistantStudioOperationRegistry();

        Map<String, Object> modelOperation = registry.allOperations().stream()
                .filter(item -> "/models".equals(item.get("path")))
                .findFirst()
                .orElseThrow(AssertionError::new);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> featureActions = (List<Map<String, Object>>) modelOperation.get("featureActions");
        Map<String, Object> syncSelected = featureActions.stream()
                .filter(item -> "syncSelected".equals(item.get("action")))
                .findFirst()
                .orElseThrow(AssertionError::new);

        assertEquals(Boolean.TRUE, syncSelected.get("mutation"));
        assertTrue(((List<?>) syncSelected.get("requiredValues")).contains("datasourceId"));
        assertTrue(((List<?>) syncSelected.get("requiredValues")).contains("physicalLocators"));
        assertFalse(((List<?>) syncSelected.get("requiredValues")).contains("payload"));
    }

    @Test
    void modelListToolShouldExposeOnlyCanonicalSearchParameter() {
        AssistantStudioOperationRegistry registry = new AssistantStudioOperationRegistry();

        Map<String, Object> modelOperation = registry.allOperations().stream()
                .filter(item -> "/models".equals(item.get("path")))
                .findFirst()
                .orElseThrow(AssertionError::new);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readTools = (List<Map<String, Object>>) modelOperation.get("readTools");
        Map<String, Object> listTool = readTools.stream()
                .filter(item -> "studio.feature.list".equals(item.get("tool")))
                .findFirst()
                .orElseThrow(AssertionError::new);
        List<?> optionalValues = (List<?>) listTool.get("optionalValues");

        assertTrue(optionalValues.contains("keyword"));
        assertTrue(optionalValues.contains("datasourceId"));
        assertFalse(optionalValues.contains("name"));
        assertFalse(optionalValues.contains("tableName"));
    }

    @Test
    void featureActionsShouldDescribeDatasourceDiscoverAsPhysicalTableDiscovery() {
        AssistantStudioOperationRegistry registry = new AssistantStudioOperationRegistry();

        Map<String, Object> datasourceOperation = registry.allOperations().stream()
                .filter(item -> "/datasources".equals(item.get("path")))
                .findFirst()
                .orElseThrow(AssertionError::new);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> featureActions = (List<Map<String, Object>>) datasourceOperation.get("featureActions");
        Map<String, Object> discover = featureActions.stream()
                .filter(item -> "discover".equals(item.get("action")))
                .findFirst()
                .orElseThrow(AssertionError::new);

        assertEquals(Boolean.FALSE, discover.get("mutation"));
        assertTrue(((List<?>) discover.get("requiredValues")).contains("id"));
        assertTrue(((List<?>) discover.get("optionalValues")).contains("keyword"));
        assertTrue(((List<?>) discover.get("aliases")).contains("listPhysicalTables"));
        assertTrue(String.valueOf(discover.get("purpose")).contains("真实物理表/视图"));
        assertTrue(String.valueOf(datasourceOperation.get("description")).contains("real physical tables"));
    }

    @Test
    void searchOperationsShouldFilterByPathAndLimit() {
        AssistantStudioOperationRegistry registry = new AssistantStudioOperationRegistry();

        List<Map<String, Object>> skills = registry.searchOperations(
                new AssistantPlanRequest(),
                java.util.Collections.<String, Object>singletonMap("path", "/quality-tasks"));

        assertEquals(1, skills.size());
        assertEquals("/quality-tasks", skills.get(0).get("path"));
        assertTrue(String.valueOf(skills.get(0).get("content")).contains("quality"));
    }
}
