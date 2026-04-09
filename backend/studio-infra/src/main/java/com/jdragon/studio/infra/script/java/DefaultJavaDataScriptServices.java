package com.jdragon.studio.infra.script.java;

import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.infra.service.DataDevelopmentSqlExecutor;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;

import java.util.List;

public class DefaultJavaDataScriptServices implements JavaDataScriptServices {

    private final DataSourceService dataSourceService;
    private final DataModelService dataModelService;
    private final DataDevelopmentSqlExecutor sqlExecutor;

    public DefaultJavaDataScriptServices(DataSourceService dataSourceService,
                                         DataModelService dataModelService,
                                         DataDevelopmentSqlExecutor sqlExecutor) {
        this.dataSourceService = dataSourceService;
        this.dataModelService = dataModelService;
        this.sqlExecutor = sqlExecutor;
    }

    @Override
    public List<DataSourceDefinition> listDatasources() {
        return dataSourceService.list();
    }

    @Override
    public DataSourceDefinition getDatasource(Long datasourceId) {
        return dataSourceService.get(datasourceId);
    }

    @Override
    public List<DataModelDefinition> listModels(Long datasourceId) {
        return dataModelService.listByDatasource(datasourceId);
    }

    @Override
    public SqlExecutionResultView executeSql(Long datasourceId, String sql) {
        return executeSql(datasourceId, sql, 100);
    }

    @Override
    public SqlExecutionResultView executeSql(Long datasourceId, String sql, Integer maxRows) {
        DataSourceDefinition datasource = dataSourceService.getInternal(datasourceId);
        return sqlExecutor.executeSql(datasource, sql, maxRows);
    }
}
