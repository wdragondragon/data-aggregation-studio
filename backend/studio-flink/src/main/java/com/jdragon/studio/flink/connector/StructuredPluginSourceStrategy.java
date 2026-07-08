package com.jdragon.studio.flink.connector;

import com.jdragon.aggregation.datasource.AbstractDataSourcePlugin;
import com.jdragon.aggregation.datasource.BaseDataSourceDTO;
import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.aggregation.pluginloader.PluginClassLoaderCloseable;

import java.util.Map;

class StructuredPluginSourceStrategy implements AggregationSourceStrategy {
    @Override
    public void readRows(AggregationFlinkTableRuntime runtime, AggregationRowEmitter emitter) throws Exception {
        ConnectorPluginRuntimeBootstrap.ensureReady(runtime.getPluginName());
        try (PluginClassLoaderCloseable loader =
                     PluginClassLoaderCloseable.newCurrentThreadClassLoaderSwapper(SourcePluginType.SOURCE, runtime.getPluginName())) {
            AbstractDataSourcePlugin plugin = loader.loadPlugin();
            BaseDataSourceDTO dto = AggregationSourceUtil.copyDataSource(runtime.getDataSourceDTO());
            String query = AggregationSourceUtil.buildQuery(runtime, runtime.getProducedDataType());
            plugin.scanQuery(dto, query, true, row -> emitOrStop(emitter, row));
        } catch (StopSourceScanException stop) {
            // used to stop plugins whose scan callback cannot otherwise be interrupted
        }
    }

    static void emitOrStop(AggregationRowEmitter emitter, Map<String, Object> row) {
        if (!emitter.emit(row)) {
            throw new StopSourceScanException();
        }
    }
}
