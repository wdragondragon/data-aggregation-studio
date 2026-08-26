package com.jdragon.studio.test;

import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.infra.script.java.DefaultJavaDataScriptServices;
import com.jdragon.studio.infra.service.DataDevelopmentSqlExecutor;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceClusterBindingService;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultJavaDataScriptServicesTest {

    @Test
    void listModelsShouldMaskHttpReaderCredentialsBeforeExposingThemToScripts() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        DataDevelopmentSqlExecutor sqlExecutor = mock(DataDevelopmentSqlExecutor.class);
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        DataModelDefinition rawModel = new DataModelDefinition();
        DataModelDefinition maskedModel = new DataModelDefinition();
        List<DataModelDefinition> rawModels = Collections.singletonList(rawModel);
        List<DataModelDefinition> maskedModels = Collections.singletonList(maskedModel);
        when(dataSourceService.getInternalForProject(7L, 11L)).thenReturn(datasource(11L, "cluster-a"));
        when(dataModelService.listByDatasource(11L)).thenReturn(rawModels);
        when(dataModelService.maskSensitiveReaderOptions(rawModels)).thenReturn(maskedModels);
        when(bindingService.filterApplicableDatasourceIds(7L, 9L, Collections.singleton(11L)))
                .thenReturn(new LinkedHashSet<Long>(Collections.singleton(11L)));

        DefaultJavaDataScriptServices services = new DefaultJavaDataScriptServices(
                dataSourceService, dataModelService, sqlExecutor, bindingService, 7L, 9L);

        assertThat(services.listModels(11L)).isSameAs(maskedModels);
        verify(bindingService).filterApplicableDatasourceIds(7L, 9L, Collections.singleton(11L));
        verify(dataModelService).maskSensitiveReaderOptions(rawModels);
    }

    @Test
    void shouldExposeOnlyDatasourcesApplicableToCurrentRuntimeCluster() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        DataDevelopmentSqlExecutor sqlExecutor = mock(DataDevelopmentSqlExecutor.class);
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        DataSourceDefinition applicable = datasource(11L, "cluster-a");
        DataSourceDefinition otherCluster = datasource(12L, "cluster-b");
        when(dataSourceService.listForProject(7L)).thenReturn(java.util.Arrays.asList(applicable, otherCluster));
        when(bindingService.filterApplicableDatasourceIds(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(new LinkedHashSet<Long>(Collections.singleton(11L)));

        DefaultJavaDataScriptServices services = new DefaultJavaDataScriptServices(
                dataSourceService, dataModelService, sqlExecutor, bindingService, 7L, 9L);

        List<DataSourceDefinition> result = services.listDatasources();
        assertThat(result).containsExactly(applicable);
        assertThat(result).doesNotContain(otherCluster);
        verify(dataSourceService, never()).getInternalForProject(7L, 12L);
    }

    @Test
    void shouldCheckRuntimeBindingBeforeExecutingSql() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        DataDevelopmentSqlExecutor sqlExecutor = mock(DataDevelopmentSqlExecutor.class);
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        DataSourceDefinition datasource = datasource(11L, "cluster-a");
        when(dataSourceService.getInternalForProject(7L, 11L)).thenReturn(datasource);
        when(bindingService.filterApplicableDatasourceIds(7L, 9L, Collections.singleton(11L)))
                .thenReturn(new LinkedHashSet<Long>(Collections.singleton(11L)));

        DefaultJavaDataScriptServices services = new DefaultJavaDataScriptServices(
                dataSourceService, dataModelService, sqlExecutor, bindingService, 7L, 9L);
        services.executeSql(11L, "select 1", 20);

        verify(bindingService).filterApplicableDatasourceIds(7L, 9L, Collections.singleton(11L));
        verify(sqlExecutor).executeSql(datasource, "select 1", 20);
    }

    @Test
    void shouldRejectSqlAgainstDatasourceFromAnotherRuntimeCluster() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        DataDevelopmentSqlExecutor sqlExecutor = mock(DataDevelopmentSqlExecutor.class);
        DatasourceClusterBindingService bindingService = mock(DatasourceClusterBindingService.class);
        when(dataSourceService.getInternalForProject(7L, 12L)).thenReturn(datasource(12L, "cluster-b"));
        when(bindingService.filterApplicableDatasourceIds(7L, 9L, Collections.singleton(12L)))
                .thenReturn(Collections.emptySet());

        DefaultJavaDataScriptServices services = new DefaultJavaDataScriptServices(
                dataSourceService, dataModelService, sqlExecutor, bindingService, 7L, 9L);

        assertThatThrownBy(() -> services.executeSql(12L, "select 1", 20))
                .hasMessage("Datasource is not applicable to the selected runtime cluster");
        verify(dataSourceService).getInternalForProject(7L, 12L);
        verify(sqlExecutor, never()).executeSql(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void shouldRejectMissingRuntimeClusterBeforeListingDatasources() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DefaultJavaDataScriptServices services = new DefaultJavaDataScriptServices(
                dataSourceService,
                mock(DataModelService.class),
                mock(DataDevelopmentSqlExecutor.class),
                mock(DatasourceClusterBindingService.class),
                7L,
                null);

        assertThatThrownBy(services::listDatasources)
                .hasMessage("Runtime cluster is required for data script execution");
        verify(dataSourceService, never()).list();
    }

    private DataSourceDefinition datasource(Long id, String name) {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(id);
        datasource.setName(name);
        return datasource;
    }
}
