package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.RuntimeDatasourceHydrationItemView;
import com.jdragon.studio.dto.model.RuntimeDatasourceHydrationResultView;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryOptionResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryResult;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Executes datasource capabilities in the process that can actually reach the datasource. */
public class RuntimeDatasourceProbeExecutor {
    private final AggregationSourceCapabilityProvider provider;
    private final DataDevelopmentSqlExecutor sqlExecutor;

    public RuntimeDatasourceProbeExecutor(AggregationSourceCapabilityProvider provider,
                                          DataDevelopmentSqlExecutor sqlExecutor) {
        this.provider = provider;
        this.sqlExecutor = sqlExecutor;
    }

    public ConnectionTestResult test(DataSourceDefinition datasource) {
        long started = System.nanoTime();
        ConnectionTestResult result = provider.testConnection(datasource);
        if (result == null) { result = new ConnectionTestResult(); result.setSuccess(false); result.setMessage("Connection test returned no result"); }
        result.setStatus(result.isSuccess() ? DataSourceConnectionStatus.AVAILABLE : DataSourceConnectionStatus.UNAVAILABLE);
        result.setDurationMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return result;
    }
    public ModelDiscoveryResult discover(DataSourceDefinition datasource, String keyword, Integer pageNo, Integer pageSize) {
        return provider.discoverModels(datasource, keyword, pageNo, pageSize);
    }
    public ModelDiscoveryOptionResult discoverOptions(DataSourceDefinition datasource, String keyword, Integer pageNo, Integer pageSize) {
        return provider.discoverModelOptions(datasource, keyword, pageNo, pageSize);
    }

    public RuntimeDatasourceHydrationResultView hydrate(DataSourceDefinition datasource,
                                                        List<String> physicalLocators) {
        List<DataModelDefinition> candidates = new ArrayList<DataModelDefinition>();
        if (physicalLocators == null || physicalLocators.isEmpty()) {
            candidates.addAll(provider.discoverModels(datasource).getModels());
        } else {
            for (String locator : physicalLocators) {
                if (locator == null || locator.trim().isEmpty()) continue;
                DataModelDefinition candidate = new DataModelDefinition();
                candidate.setDatasourceId(datasource.getId());
                candidate.setName(locator.trim());
                candidate.setPhysicalLocator(locator.trim());
                Map<String, Object> metadata = new LinkedHashMap<String, Object>();
                metadata.put("sourceType", datasource.getTypeCode());
                metadata.put("discoveryMode", "AUTO");
                metadata.put("physicalName", locator.trim());
                candidate.setTechnicalMetadata(metadata);
                candidate.setBusinessMetadata(new LinkedHashMap<String, Object>());
                candidates.add(candidate);
            }
        }
        RuntimeDatasourceHydrationResultView result = new RuntimeDatasourceHydrationResultView();
        for (AggregationSourceCapabilityProvider.HydrationResult hydrated
                : provider.hydrateDiscoveredModels(datasource, candidates)) {
            RuntimeDatasourceHydrationItemView item = new RuntimeDatasourceHydrationItemView();
            item.setPhysicalLocator(hydrated.getPhysicalLocator());
            item.setSuccess(hydrated.isSuccess());
            item.setDefinition(hydrated.getDefinition());
            item.setMessage(hydrated.getErrorMessage());
            result.getItems().add(item);
        }
        return result;
    }

    public List<Map<String, Object>> preview(DataSourceDefinition datasource,
                                             DataModelDefinition model,
                                             Integer limit) {
        return provider.preview(datasource, model, limit == null ? 20 : Math.max(1, Math.min(limit, 1000)));
    }

    public SqlExecutionResultView query(DataSourceDefinition datasource,
                                        String sql,
                                        List<Object> parameters,
                                        Integer maxRows) {
        return sqlExecutor.executePreparedQuery(datasource, sql,
                parameters == null ? new ArrayList<Object>() : parameters,
                maxRows == null ? 1 : Math.max(1, Math.min(maxRows, 1000)));
    }
}
