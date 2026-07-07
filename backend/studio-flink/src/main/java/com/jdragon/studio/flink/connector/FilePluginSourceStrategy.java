package com.jdragon.studio.flink.connector;

import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.aggregation.datasource.file.FileHelper;
import com.jdragon.aggregation.pluginloader.PluginClassLoaderCloseable;

import java.util.Map;

class FilePluginSourceStrategy implements AggregationSourceStrategy {
    @Override
    public void readRows(AggregationFlinkTableRuntime runtime, AggregationRowEmitter emitter) throws Exception {
        try (PluginClassLoaderCloseable loader =
                     PluginClassLoaderCloseable.newCurrentThreadClassLoaderSwapper(SourcePluginType.SOURCE, runtime.getPluginName())) {
            FileHelper fileHelper = loader.loadPlugin();
            if (!fileHelper.connect(runtime.getConnectionConfig())) {
                throw new IllegalStateException("Failed to connect file source: " + runtime.getPluginName());
            }
            String path = resolveFilePath(runtime);
            String fileType = resolveFileType(runtime, path);
            fileHelper.readFile(path, fileType, row -> emitOrStop(emitter, row), runtime.getExtConfig());
        } catch (Exception ex) {
            if (!isStopSourceScan(ex)) {
                throw ex;
            }
            // stop requested by Flink reader limit
        }
    }

    private boolean isStopSourceScan(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof StopSourceScanException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void emitOrStop(AggregationRowEmitter emitter, Map<String, Object> row) {
        if (!emitter.emit(row)) {
            throw new StopSourceScanException();
        }
    }

    private String resolveFilePath(AggregationFlinkTableRuntime runtime) {
        Object path = runtime.getModelMetadata().get("path");
        if (path == null) {
            path = runtime.getModelMetadata().get("physicalName");
        }
        if (path == null) {
            path = runtime.getPhysicalLocator();
        }
        if (path == null) {
            path = runtime.getTableName();
        }
        String value = String.valueOf(path);
        Object root = runtime.getModelMetadata().get("rootPath");
        if (root != null && !value.startsWith("/") && !value.contains(":")) {
            String rootPath = String.valueOf(root);
            return rootPath.endsWith("/") ? rootPath + value : rootPath + "/" + value;
        }
        return value;
    }

    private String resolveFileType(AggregationFlinkTableRuntime runtime, String path) {
        Object configured = runtime.getModelMetadata().get("fileType");
        if (configured != null && AggregationSourceUtil.hasText(String.valueOf(configured))) {
            return String.valueOf(configured).trim().toLowerCase();
        }
        String lower = path == null ? "" : path.toLowerCase();
        if (lower.endsWith(".jsonl") || lower.endsWith(".ndjson")) {
            return "jsonl";
        }
        if (lower.endsWith(".json")) {
            return "json";
        }
        if (lower.endsWith(".efile")) {
            return "efile";
        }
        if (lower.endsWith(".parquet")) {
            return "parquet";
        }
        if (lower.endsWith(".avro")) {
            return "avro";
        }
        if (lower.endsWith(".xml")) {
            return "xml";
        }
        return "csv";
    }
}
