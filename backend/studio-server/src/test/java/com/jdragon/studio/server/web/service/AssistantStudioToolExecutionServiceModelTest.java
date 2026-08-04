package com.jdragon.studio.server.web.service;

import com.jdragon.studio.dto.model.DataModelDatasourceOptionView;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.infra.service.AssistantStudioOperationRegistry;
import com.jdragon.studio.infra.service.DataModelService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantStudioToolExecutionServiceModelTest {

    @Test
    void modelListShouldUseClusterScopedSelectorOptionsWhenClusterIsProvided() {
        AssistantStudioToolExecutionService service = mock(AssistantStudioToolExecutionService.class, CALLS_REAL_METHODS);
        DataModelService dataModelService = mock(DataModelService.class);
        ReflectionTestUtils.setField(service, "dataModelService", dataModelService);
        PageView<DataModelDatasourceOptionView> expected = PageView.of(
                2, 10, 0L, Collections.<DataModelDatasourceOptionView>emptyList());
        when(dataModelService.listSelectorOptions("MYSQL", 11L, 7L, "orders", 2, 10))
                .thenReturn(expected);
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("datasourceType", "MYSQL");
        params.put("datasourceId", 11L);
        params.put("runtimeClusterId", 7L);
        params.put("keyword", "orders");
        params.put("pageNo", 2);
        params.put("pageSize", 10);

        Object result = ReflectionTestUtils.invokeMethod(service, "executeList", "/models", params);

        assertSame(expected, result);
        verify(dataModelService).listSelectorOptions("MYSQL", 11L, 7L, "orders", 2, 10);
    }

    @Test
    void modelPreviewActionShouldUseExplicitRuntimeCluster() {
        AssistantStudioToolExecutionService service = mock(AssistantStudioToolExecutionService.class, CALLS_REAL_METHODS);
        DataModelService dataModelService = mock(DataModelService.class);
        ReflectionTestUtils.setField(service, "dataModelService", dataModelService);
        ReflectionTestUtils.setField(service, "operationRegistry", new AssistantStudioOperationRegistry());
        DataModelDefinition detail = new DataModelDefinition();
        List<Map<String, Object>> rows = Collections.singletonList(
                Collections.<String, Object>singletonMap("id", Long.valueOf(1L)));
        when(dataModelService.get(5L)).thenReturn(detail);
        when(dataModelService.maskSensitiveReaderOptions(detail)).thenReturn(detail);
        when(dataModelService.preview(5L, 30, 7L)).thenReturn(rows);
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("path", "/models");
        params.put("action", "preview");
        params.put("id", 5L);
        params.put("runtimeClusterId", 7L);
        params.put("limit", 30);

        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("interfaceCode", "studio.feature.action");
        request.put("params", params);

        Map<String, Object> response = service.execute(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> preview = (Map<String, Object>) response.get("data");
        assertEquals(Boolean.FALSE, response.get("mutation"));
        assertEquals(Boolean.FALSE, response.get("requiresConfirmation"));
        assertSame(detail, preview.get("detail"));
        assertSame(rows, preview.get("previewRows"));
        assertSame(rows, preview.get("sampleRows"));
        assertEquals(Integer.valueOf(1), preview.get("previewRowCount"));
        verify(dataModelService).preview(5L, 30, 7L);
    }

    @Test
    void missingRuntimeClusterShouldNameTheActualRequiredParameter() {
        AssistantStudioToolExecutionService service = mock(AssistantStudioToolExecutionService.class, CALLS_REAL_METHODS);
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("id", 5L);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "executeModelAction", "preview", params));

        assertEquals("assistant tool runtimeClusterId is required", error.getMessage());
    }
}
