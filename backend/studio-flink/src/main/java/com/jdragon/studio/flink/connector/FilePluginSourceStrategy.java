package com.jdragon.studio.flink.connector;

import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.aggregation.datasource.file.FileHelper;
import com.jdragon.aggregation.pluginloader.PluginClassLoaderCloseable;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class FilePluginSourceStrategy implements AggregationSourceStrategy {
    @Override
    public void readRows(AggregationFlinkTableRuntime runtime, AggregationRowEmitter emitter) throws Exception {
        ConnectorPluginRuntimeBootstrap.ensureReady(runtime.getPluginName());
        try (PluginClassLoaderCloseable loader =
                     PluginClassLoaderCloseable.newCurrentThreadClassLoaderSwapper(SourcePluginType.SOURCE, runtime.getPluginName())) {
            FileHelper fileHelper = loader.loadPlugin();
            if (!fileHelper.connect(runtime.getConnectionConfig())) {
                throw new IllegalStateException("Failed to connect file source: " + runtime.getPluginName());
            }
            List<ResolvedFilePath> paths = FilePathPushdownResolver.resolve(runtime);
            for (ResolvedFilePath resolvedPath : paths) {
                for (ResolvedFilePath concretePath : FilePathExpansion.expand(fileHelper, runtime, resolvedPath)) {
                    String path = concretePath.getPath();
                    runtime.addResolvedFilePath(path);
                    String fileType = resolveFileType(runtime, path);
                    Map<String, LocalDate> contextValues = concretePath.getContextValues();
                    try {
                        fileHelper.readFile(path, fileType, row -> emitOrStop(emitter, enrichPathContext(row, contextValues)),
                                runtime.getExtConfig());
                    } catch (Exception ex) {
                        if (!FilePathExpansion.isMissingFile(ex)) {
                            throw ex;
                        }
                    }
                }
            }
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

    private Map<String, Object> enrichPathContext(Map<String, Object> row, Map<String, LocalDate> contextValues) {
        if (contextValues == null || contextValues.isEmpty()) {
            return row;
        }
        Map<String, Object> enriched = new LinkedHashMap<String, Object>();
        if (row != null) {
            enriched.putAll(row);
        }
        enriched.putAll(contextValues);
        return enriched;
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
