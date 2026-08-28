package com.jdragon.studio.infra.service;

import com.jdragon.aggregation.transfer.TransferContractException;
import com.jdragon.aggregation.transfer.TransferSpecNormalizer;
import com.jdragon.aggregation.transfer.model.CheckpointRecoveryMode;
import com.jdragon.aggregation.transfer.model.TransferEndpoint;
import com.jdragon.aggregation.transfer.model.TransferMapping;
import com.jdragon.aggregation.transfer.model.TransferPolicy;
import com.jdragon.aggregation.transfer.model.TransferRuntimeOptions;
import com.jdragon.aggregation.transfer.model.TransferSelection;
import com.jdragon.aggregation.transfer.model.TransferSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class StudioFileTransferContractAdapter {

    public TransferSpec map(Map<String, Object> configuration) {
        if (configuration == null) {
            throw invalid("spec", "file transfer configuration is required", null);
        }
        Map<String, Object> selection = mapValue(configuration.get("selection"));
        Map<String, Object> mapping = mapValue(configuration.get("mapping"));
        Map<String, Object> runtime = mapValue(configuration.get("runtime"));
        TransferRuntimeOptions defaults = TransferRuntimeOptions.defaults();
        TransferPolicy policy = TransferSpecNormalizer.normalizePolicy(
                mapValue(configuration.get("policy")));
        try {
            TransferSpec spec = new TransferSpec(
                    integer(configuration.get("schemaVersion"), 1, "schemaVersion"),
                    endpoint(configuration, "source"),
                    endpoint(configuration, "target"),
                    new TransferSelection(
                            required(selection.get("rootPath"), "selection.rootPath"),
                            strings(selection.get("paths")),
                            bool(selection.get("recursive"), true),
                            strings(selection.get("includeGlobs")),
                            text(selection.get("includeRegex"), null),
                            strings(selection.get("excludeGlobs")),
                            nullableLong(selection.get("minSize"), "selection.minSize"),
                            nullableLong(selection.get("maxSize"), "selection.maxSize"),
                            nullableLong(selection.get("modifiedAfterMillis"),
                                    "selection.modifiedAfterMillis"),
                            nullableLong(selection.get("modifiedBeforeMillis"),
                                    "selection.modifiedBeforeMillis"),
                            integer(selection.get("maxFiles"), 100_000, "selection.maxFiles")),
                    new TransferMapping(
                            required(mapping.get("targetRootPath"), "mapping.targetRootPath"),
                            bool(mapping.get("preserveRelativePath"), true),
                            text(mapping.get("targetPathTemplate"), null)),
                    policy,
                    new TransferRuntimeOptions(
                            integer(runtime.get("concurrency"), defaults.concurrency(),
                                    "runtime.concurrency"),
                            integer(runtime.get("maxRetries"), defaults.maxRetries(),
                                    "runtime.maxRetries"),
                            longs(runtime.get("retryBackoffMillis"), defaults.retryBackoffMillis(),
                                    "runtime.retryBackoffMillis"),
                            longValue(runtime.get("chunkSizeBytes"), defaults.chunkSizeBytes(),
                                    "runtime.chunkSizeBytes"),
                            longValue(runtime.get("checkpointIntervalMillis"),
                                    defaults.checkpointIntervalMillis(),
                                    "runtime.checkpointIntervalMillis"),
                            longValue(runtime.get("maxBytesPerSecond"), defaults.maxBytesPerSecond(),
                                    "runtime.maxBytesPerSecond"),
                            enumValue(CheckpointRecoveryMode.class,
                                    runtime.get("checkpointRecoveryMode"),
                                    defaults.checkpointRecoveryMode(),
                                    "runtime.checkpointRecoveryMode")),
                    text(configuration.get("timeZone"), "Asia/Shanghai"),
                    stringMap(configuration.get("parameters")));
            return TransferSpecNormalizer.normalize(spec);
        } catch (TransferContractException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw invalid("spec", exception.getMessage(), exception);
        }
    }

    private TransferEndpoint endpoint(Map<String, Object> configuration, String field) {
        Map<String, Object> endpoint = mapValue(configuration.get(field));
        String plugin = text(endpoint.get("plugin"), text(endpoint.get("type"), null));
        if (plugin == null) {
            throw invalid(field + ".plugin", field + ".plugin is required", null);
        }
        return new TransferEndpoint(plugin, text(endpoint.get("identity"), plugin),
                mapValue(endpoint.get("config")));
    }

    private Map<String, Object> mapValue(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) {
            source.forEach((key, item) -> {
                if (key != null) {
                    result.put(String.valueOf(key), item);
                }
            });
        }
        return result;
    }

    private Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        mapValue(value).forEach((key, item) -> {
            if (item != null) {
                result.put(key, String.valueOf(item));
            }
        });
        return result;
    }

    private List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?> source) {
            source.forEach(item -> {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            });
        }
        return result;
    }

    private List<Long> longs(Object value, List<Long> fallback, String field) {
        if (!(value instanceof Iterable<?> source)) {
            return fallback;
        }
        List<Long> result = new ArrayList<>();
        for (Object item : source) {
            result.add(longValue(item, 0L, field));
        }
        return result.isEmpty() ? fallback : result;
    }

    private boolean bool(Object value, boolean fallback) {
        return value == null || String.valueOf(value).isBlank()
                ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private int integer(Object value, int fallback, String field) {
        long result = longValue(value, fallback, field);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw invalid(field, field + " is outside the integer range", null);
        }
        return (int) result;
    }

    private Long nullableLong(Object value, String field) {
        return value == null || String.valueOf(value).isBlank()
                ? null : longValue(value, 0L, field);
    }

    private long longValue(Object value, long fallback, String field) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return value instanceof Number number
                    ? number.longValue() : Long.parseLong(String.valueOf(value));
        } catch (RuntimeException exception) {
            throw invalid(field, field + " must be an integer", exception);
        }
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, Object value, E fallback, String field) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type,
                    String.valueOf(value).trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw invalid(field, field + " is invalid: " + value, exception);
        }
    }

    private String required(Object value, String field) {
        String result = text(value, null);
        if (result == null) {
            throw invalid(field, field + " is required", null);
        }
        return result;
    }

    private String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank()
                ? fallback : String.valueOf(value);
    }

    private TransferContractException invalid(String field, String message, Throwable cause) {
        return new TransferContractException("INVALID_TRANSFER_SPEC", field, message, cause);
    }
}
