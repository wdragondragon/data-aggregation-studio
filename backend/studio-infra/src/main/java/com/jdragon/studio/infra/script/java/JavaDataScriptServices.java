package com.jdragon.studio.infra.script.java;

import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.SqlExecutionResultView;

import java.util.List;

public interface JavaDataScriptServices {
    List<DataSourceDefinition> listDatasources();

    DataSourceDefinition getDatasource(Long datasourceId);

    List<DataModelDefinition> listModels(Long datasourceId);

    SqlExecutionResultView executeSql(Long datasourceId, String sql);

    SqlExecutionResultView executeSql(Long datasourceId, String sql, Integer maxRows);
}
