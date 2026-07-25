package com.jdragon.studio.infra.script.java;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.infra.service.DataDevelopmentSqlExecutor;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceClusterBindingService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class DefaultJavaDataScriptServices implements JavaDataScriptServices {

    private final DataSourceService dataSourceService;
    private final DataModelService dataModelService;
    private final DataDevelopmentSqlExecutor sqlExecutor;
    private final DatasourceClusterBindingService datasourceClusterBindingService;
    private final Long projectId;
    private final Long runtimeClusterId;

    public DefaultJavaDataScriptServices(DataSourceService dataSourceService,
                                         DataModelService dataModelService,
                                         DataDevelopmentSqlExecutor sqlExecutor,
                                         DatasourceClusterBindingService datasourceClusterBindingService,
                                         Long projectId,
                                         Long runtimeClusterId) {
        this.dataSourceService = dataSourceService;
        this.dataModelService = dataModelService;
        this.sqlExecutor = sqlExecutor;
        this.datasourceClusterBindingService = datasourceClusterBindingService;
        this.projectId = projectId;
        this.runtimeClusterId = runtimeClusterId;
    }

    @Override
    public List<DataSourceDefinition> listDatasources() {
        assertRuntimeScope();
        List<DataSourceDefinition> candidates = dataSourceService.list();
        Set<Long> candidateIds = new LinkedHashSet<Long>();
        for (DataSourceDefinition candidate : candidates) {
            if (candidate != null && candidate.getId() != null) {
                candidateIds.add(candidate.getId());
            }
        }
        Set<Long> applicableIds = datasourceClusterBindingService.filterApplicableDatasourceIds(
                projectId, runtimeClusterId, candidateIds);
        List<DataSourceDefinition> result = new ArrayList<DataSourceDefinition>();
        for (DataSourceDefinition candidate : candidates) {
            if (candidate != null && applicableIds.contains(candidate.getId())) {
                result.add(candidate);
            }
        }
        return result;
    }

    @Override
    public DataSourceDefinition getDatasource(Long datasourceId) {
        assertDatasourceApplicable(datasourceId);
        return dataSourceService.get(datasourceId);
    }

    @Override
    public List<DataModelDefinition> listModels(Long datasourceId) {
        assertDatasourceApplicable(datasourceId);
        return dataModelService.maskSensitiveReaderOptions(
                dataModelService.listByDatasource(datasourceId));
    }

    @Override
    public SqlExecutionResultView executeSql(Long datasourceId, String sql) {
        return executeSql(datasourceId, sql, 100);
    }

    @Override
    public SqlExecutionResultView executeSql(Long datasourceId, String sql, Integer maxRows) {
        assertDatasourceApplicable(datasourceId);
        DataSourceDefinition datasource = dataSourceService.getInternal(datasourceId);
        return sqlExecutor.executeSql(datasource, sql, maxRows);
    }

    private void assertDatasourceApplicable(Long datasourceId) {
        assertRuntimeScope();
        if (datasourceId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Datasource is required");
        }
        Set<Long> applicableIds = datasourceClusterBindingService.filterApplicableDatasourceIds(
                projectId, runtimeClusterId, Collections.singleton(datasourceId));
        if (!applicableIds.contains(datasourceId)) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "Datasource is not applicable to the selected runtime cluster");
        }
    }

    private void assertRuntimeScope() {
        if (projectId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Project is required for data script execution");
        }
        if (runtimeClusterId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Runtime cluster is required for data script execution");
        }
    }
}
