package com.jdragon.studio.server.web.service;

import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.RuntimeClusterView;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.RuntimeClusterService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantStudioToolExecutionServiceRuntimeClusterTest {

    @Test
    void runtimeClusterOptionsShouldIntersectProjectAuthorizationAndRelatedDatasources() {
        AssistantStudioToolExecutionService service = mock(AssistantStudioToolExecutionService.class, CALLS_REAL_METHODS);
        RuntimeClusterService runtimeClusterService = mock(RuntimeClusterService.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        ReflectionTestUtils.setField(service, "runtimeClusterService", runtimeClusterService);
        ReflectionTestUtils.setField(service, "dataSourceService", dataSourceService);
        ReflectionTestUtils.setField(service, "dataModelService", dataModelService);

        RuntimeClusterView cluster1 = cluster(1L);
        RuntimeClusterView cluster2 = cluster(2L);
        RuntimeClusterView cluster3 = cluster(3L);
        when(runtimeClusterService.options(null)).thenReturn(Arrays.asList(cluster1, cluster2, cluster3));
        when(dataSourceService.get(11L)).thenReturn(datasource(1L, 2L));
        when(dataSourceService.get(12L)).thenReturn(datasource(2L, 3L));
        DataModelDefinition model = new DataModelDefinition();
        model.setDatasourceId(12L);
        when(dataModelService.get(5L)).thenReturn(model);
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("datasourceIds", Collections.singletonList(11L));
        params.put("modelIds", Collections.singletonList(5L));

        @SuppressWarnings("unchecked")
        List<RuntimeClusterView> result = (List<RuntimeClusterView>) ReflectionTestUtils.invokeMethod(
                service, "executeList", "/runtime-clusters", params);

        assertEquals(1, result.size());
        assertSame(cluster2, result.get(0));
        verify(runtimeClusterService).options(null);
        verify(dataSourceService).get(11L);
        verify(dataSourceService).get(12L);
        verify(dataModelService).get(5L);
    }

    @Test
    void runtimeClusterOptionsShouldReturnEmptyWhenApplicableIntersectionIsEmpty() {
        AssistantStudioToolExecutionService service = mock(AssistantStudioToolExecutionService.class, CALLS_REAL_METHODS);
        RuntimeClusterService runtimeClusterService = mock(RuntimeClusterService.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        ReflectionTestUtils.setField(service, "runtimeClusterService", runtimeClusterService);
        ReflectionTestUtils.setField(service, "dataSourceService", dataSourceService);
        when(runtimeClusterService.options(null)).thenReturn(Arrays.asList(cluster(1L), cluster(2L)));
        when(dataSourceService.get(11L)).thenReturn(datasource(1L));
        when(dataSourceService.get(12L)).thenReturn(datasource(2L));
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("datasourceIds", Arrays.asList(11L, 12L));

        @SuppressWarnings("unchecked")
        List<RuntimeClusterView> result = (List<RuntimeClusterView>) ReflectionTestUtils.invokeMethod(
                service, "executeList", "/runtime-clusters", params);

        assertEquals(Collections.emptyList(), result);
    }

    @Test
    void runtimeClusterOptionsShouldKeepExplicitEmptyApplicabilityAsNoCandidates() {
        AssistantStudioToolExecutionService service = mock(AssistantStudioToolExecutionService.class, CALLS_REAL_METHODS);
        RuntimeClusterService runtimeClusterService = mock(RuntimeClusterService.class);
        ReflectionTestUtils.setField(service, "runtimeClusterService", runtimeClusterService);
        when(runtimeClusterService.options(null)).thenReturn(Arrays.asList(cluster(1L), cluster(2L)));
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("applicableClusterIds", Collections.emptyList());

        @SuppressWarnings("unchecked")
        List<RuntimeClusterView> result = (List<RuntimeClusterView>) ReflectionTestUtils.invokeMethod(
                service, "executeList", "/runtime-clusters", params);

        assertEquals(Collections.emptyList(), result);
    }

    @Test
    void runtimeClusterOptionsWithoutResourceEvidenceShouldKeepAllAuthorizedCandidates() {
        AssistantStudioToolExecutionService service = mock(AssistantStudioToolExecutionService.class, CALLS_REAL_METHODS);
        RuntimeClusterService runtimeClusterService = mock(RuntimeClusterService.class);
        ReflectionTestUtils.setField(service, "runtimeClusterService", runtimeClusterService);
        List<RuntimeClusterView> options = Arrays.asList(cluster(1L), cluster(2L));
        when(runtimeClusterService.options(null)).thenReturn(options);

        Object result = ReflectionTestUtils.invokeMethod(
                service, "executeList", "/runtime-clusters", new LinkedHashMap<String, Object>());

        assertSame(options, result);
    }

    private RuntimeClusterView cluster(Long id) {
        RuntimeClusterView view = new RuntimeClusterView();
        view.setId(id);
        return view;
    }

    private DataSourceDefinition datasource(Long... clusterIds) {
        DataSourceDefinition definition = new DataSourceDefinition();
        definition.setApplicableClusterIds(Arrays.asList(clusterIds));
        return definition;
    }
}
