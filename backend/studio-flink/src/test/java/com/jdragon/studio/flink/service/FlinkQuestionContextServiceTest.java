package com.jdragon.studio.flink.service;

import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.request.FlinkQuestionAskRequest;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceClusterBindingService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.RuntimeClusterSelectionService;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlinkQuestionContextServiceTest {

    @Test
    void redactsHttpLocatorCredentialsBeforeBuildingLlmPrompt() {
        DataModelService dataModelService = mock(DataModelService.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        RuntimeClusterSelectionService runtimeClusterSelectionService = mock(RuntimeClusterSelectionService.class);
        DatasourceClusterBindingService datasourceClusterBindingService = mock(DatasourceClusterBindingService.class);
        ProjectResourceAccessService projectResourceAccessService = mock(ProjectResourceAccessService.class);
        FlinkQuestionContextService service = new FlinkQuestionContextService(
                dataModelService,
                dataSourceService,
                runtimeClusterSelectionService,
                datasourceClusterBindingService,
                projectResourceAccessService);

        DataModelDefinition model = new DataModelDefinition();
        model.setId(7L);
        model.setDatasourceId(3L);
        model.setName("customers");
        model.setPhysicalLocator("http://api-user:api-pass@127.0.0.1:19090/customers/{customer_id}"
                + "?access_token=raw-secret#private-fragment");
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(3L);
        datasource.setTypeCode("http");

        when(dataModelService.get(7L)).thenReturn(model);
        when(dataSourceService.getInternal(3L)).thenReturn(datasource);
        when(projectResourceAccessService.requireCurrentProjectId()).thenReturn(2L);
        when(runtimeClusterSelectionService.resolveForSave(2L, 9L)).thenReturn(9L);
        when(datasourceClusterBindingService.filterApplicableDatasourceIds(
                2L, 9L, Collections.singleton(3L))).thenReturn(Collections.singleton(3L));
        FlinkQuestionAskRequest request = new FlinkQuestionAskRequest();
        request.setQuestion("query customers");
        request.setModelIds(Collections.singletonList(7L));
        request.setRuntimeClusterId(9L);

        String prompt = service.build(request).getPromptContext();

        assertTrue(prompt.contains("physicalLocator: http://127.0.0.1:19090/customers/{customer_id}"));
        assertFalse(prompt.contains("api-user"));
        assertFalse(prompt.contains("api-pass"));
        assertFalse(prompt.contains("raw-secret"));
        assertFalse(prompt.contains("private-fragment"));
    }

    @Test
    void rejectsExplicitModelOutsideSelectedRuntimeClusterBeforeBuildingPrompt() {
        DataModelService dataModelService = mock(DataModelService.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        RuntimeClusterSelectionService runtimeClusterSelectionService = mock(RuntimeClusterSelectionService.class);
        DatasourceClusterBindingService datasourceClusterBindingService = mock(DatasourceClusterBindingService.class);
        ProjectResourceAccessService projectResourceAccessService = mock(ProjectResourceAccessService.class);
        FlinkQuestionContextService service = new FlinkQuestionContextService(
                dataModelService,
                dataSourceService,
                runtimeClusterSelectionService,
                datasourceClusterBindingService,
                projectResourceAccessService);

        DataModelDefinition model = new DataModelDefinition();
        model.setId(7L);
        model.setDatasourceId(3L);
        when(dataModelService.get(7L)).thenReturn(model);
        when(projectResourceAccessService.requireCurrentProjectId()).thenReturn(2L);
        when(runtimeClusterSelectionService.resolveForSave(2L, 9L)).thenReturn(9L);
        when(datasourceClusterBindingService.filterApplicableDatasourceIds(
                2L, 9L, Collections.singleton(3L))).thenReturn(Collections.emptySet());
        FlinkQuestionAskRequest request = new FlinkQuestionAskRequest();
        request.setQuestion("query customers");
        request.setModelIds(Collections.singletonList(7L));
        request.setRuntimeClusterId(9L);

        StudioException exception = assertThrows(StudioException.class, () -> service.build(request));

        assertTrue(exception.getMessage().contains("not applicable to the selected runtime cluster"));
        verify(dataSourceService, never()).getInternal(3L);
    }
}
