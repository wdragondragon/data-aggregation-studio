package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.QualityTaskDefinitionView;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityTaskExecutionServiceRuntimeRoutingTest {

    @Test
    void validationShouldQueryTheExplicitRuntimeCluster() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DatasourceTypeCapabilityService capabilityService = mock(DatasourceTypeCapabilityService.class);
        QualitySqlTemplateService templateService = mock(QualitySqlTemplateService.class);
        RuntimeDatasourceProbeRouter probeRouter = mock(RuntimeDatasourceProbeRouter.class);
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(11L);
        datasource.setTypeCode("mysql");
        SqlExecutionResultView sqlResult = new SqlExecutionResultView();

        when(dataSourceService.getInternal(11L)).thenReturn(datasource);
        when(capabilityService.isSqlExecutable("mysql")).thenReturn(true);
        when(templateService.resolveSql(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any())).thenReturn("select 1");
        when(probeRouter.query(datasource, 46L, "select 1", Collections.emptyList(), 20)).thenReturn(sqlResult);

        QualityTaskExecutionPlanService service = new QualityTaskExecutionPlanService(
                dataSourceService, capabilityService, templateService, new ObjectMapper(), probeRouter);
        QualityTaskDefinitionView definition = new QualityTaskDefinitionView();
        definition.setDatasourceId(11L);
        definition.setRuntimeClusterId(46L);
        definition.setRuleSnapshot(Collections.singletonMap("logicSql", "select 1"));

        assertTrue(service.validate(definition).getValid());
        verify(probeRouter).query(datasource, 46L, "select 1", Collections.emptyList(), 20);
    }
}
