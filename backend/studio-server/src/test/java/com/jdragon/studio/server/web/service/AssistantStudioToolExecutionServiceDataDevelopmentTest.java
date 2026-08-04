package com.jdragon.studio.server.web.service;

import com.jdragon.studio.dto.model.DataSourceOptionView;
import com.jdragon.studio.infra.service.DataDevelopmentService;
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

class AssistantStudioToolExecutionServiceDataDevelopmentTest {

    @Test
    void datasourceCandidatesShouldUseExplicitRuntimeCluster() {
        AssistantStudioToolExecutionService service = mock(AssistantStudioToolExecutionService.class, CALLS_REAL_METHODS);
        DataDevelopmentService dataDevelopmentService = mock(DataDevelopmentService.class);
        ReflectionTestUtils.setField(service, "dataDevelopmentService", dataDevelopmentService);
        List<DataSourceOptionView> expected = Collections.emptyList();
        when(dataDevelopmentService.listSqlCapableDatasourceOptions(7L)).thenReturn(expected);
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("view", "datasources");
        params.put("runtimeClusterId", 7L);

        Object result = ReflectionTestUtils.invokeMethod(service, "executeList", "/data-development", params);

        assertSame(expected, result);
        verify(dataDevelopmentService).listSqlCapableDatasourceOptions(7L);
    }

    @Test
    void datasourceCandidatesShouldRejectMissingRuntimeClusterAtToolBoundary() {
        AssistantStudioToolExecutionService service = mock(AssistantStudioToolExecutionService.class, CALLS_REAL_METHODS);
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("view", "datasources");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "executeList", "/data-development", params));

        assertEquals("assistant tool runtimeClusterId is required", error.getMessage());
    }
}
