package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.RunMetricSummaryView;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RunMetricSummaryMapper {

    public RunMetricSummaryView fromEntity(RunRecordEntity entity) {
        if (entity == null) {
            return null;
        }
        RunMetricSummaryView summary = new RunMetricSummaryView();
        summary.setCollectedRecords(firstNonNull(entity.getCollectedRecords(), extractMetric(entity.getResultJson(), "collectedRecords", "totalRecords")));
        summary.setReadSucceedRecords(firstNonNull(entity.getReadSucceedRecords(), extractMetric(entity.getResultJson(), "readSucceedRecords")));
        summary.setReadFailedRecords(firstNonNull(entity.getReadFailedRecords(), extractMetric(entity.getResultJson(), "readFailedRecords")));
        summary.setWriteSucceedRecords(firstNonNull(entity.getWriteSucceedRecords(), extractMetric(entity.getResultJson(), "writeSucceedRecords")));
        summary.setWriteFailedRecords(firstNonNull(entity.getWriteFailedRecords(), extractMetric(entity.getResultJson(), "writeFailedRecords")));
        summary.setFailedRecords(firstNonNull(entity.getFailedRecords(), extractMetric(entity.getResultJson(), "failedRecords", "errorRecords")));
        summary.setSuccessRecords(firstNonNull(entity.getSuccessRecords(), firstNonNull(extractMetric(entity.getResultJson(), "successRecords"),
                firstNonNull(summary.getWriteSucceedRecords(),
                        calculateSuccessRecordsOrNull(summary.getReadSucceedRecords(), summary.getWriteFailedRecords())))));
        Long transformerSuccess = firstNonNull(entity.getTransformerSuccessRecords(), extractMetric(entity.getResultJson(), "transformerSuccessRecords", "transformerSuccess"));
        Long transformerFailed = firstNonNull(entity.getTransformerFailedRecords(), extractMetric(entity.getResultJson(), "transformerFailedRecords", "transformerError"));
        Long transformerFilter = firstNonNull(entity.getTransformerFilterRecords(), extractMetric(entity.getResultJson(), "transformerFilterRecords", "transformerFilter"));
        Long transformerTotal = firstNonNull(entity.getTransformerTotalRecords(), extractMetric(entity.getResultJson(), "transformerTotalRecords"));
        if (transformerTotal == null && hasAnyValue(transformerSuccess, transformerFailed, transformerFilter)) {
            transformerTotal = safeValue(transformerSuccess) + safeValue(transformerFailed) + safeValue(transformerFilter);
        }
        summary.setTransformerTotalRecords(transformerTotal);
        summary.setTransformerSuccessRecords(transformerSuccess);
        summary.setTransformerFailedRecords(transformerFailed);
        summary.setTransformerFilterRecords(transformerFilter);
        normalizeFailedTerminalMetrics(summary, isFailedStatus(entity.getStatus()));
        return summary;
    }

    public void applyToEntity(RunRecordEntity entity, Map<String, Object> payload) {
        if (entity == null) {
            return;
        }
        RunMetricSummaryView summary = fromPayload(payload);
        entity.setCollectedRecords(summary.getCollectedRecords());
        entity.setReadSucceedRecords(summary.getReadSucceedRecords());
        entity.setReadFailedRecords(summary.getReadFailedRecords());
        entity.setWriteSucceedRecords(summary.getWriteSucceedRecords());
        entity.setWriteFailedRecords(summary.getWriteFailedRecords());
        entity.setFailedRecords(summary.getFailedRecords());
        entity.setSuccessRecords(summary.getSuccessRecords());
        entity.setTransformerTotalRecords(summary.getTransformerTotalRecords());
        entity.setTransformerSuccessRecords(summary.getTransformerSuccessRecords());
        entity.setTransformerFailedRecords(summary.getTransformerFailedRecords());
        entity.setTransformerFilterRecords(summary.getTransformerFilterRecords());
    }

    public RunMetricSummaryView fromPayload(Map<String, Object> payload) {
        RunMetricSummaryView summary = new RunMetricSummaryView();
        Map<String, Object> summaryMap = summaryMap(payload);
        summary.setCollectedRecords(defaultZero(firstNonNull(asLong(summaryMap.get("collectedRecords")), asLong(summaryMap.get("totalRecords")))));
        summary.setReadSucceedRecords(defaultZero(asLong(summaryMap.get("readSucceedRecords"))));
        summary.setReadFailedRecords(defaultZero(asLong(summaryMap.get("readFailedRecords"))));
        Long writeSucceedRecords = asLong(summaryMap.get("writeSucceedRecords"));
        summary.setWriteSucceedRecords(defaultZero(writeSucceedRecords));
        summary.setWriteFailedRecords(defaultZero(asLong(summaryMap.get("writeFailedRecords"))));
        summary.setFailedRecords(defaultZero(firstNonNull(asLong(summaryMap.get("failedRecords")), asLong(summaryMap.get("errorRecords")))));
        summary.setSuccessRecords(defaultZero(firstNonNull(asLong(summaryMap.get("successRecords")),
                firstNonNull(writeSucceedRecords,
                        calculateSuccessRecords(summary.getReadSucceedRecords(), summary.getWriteFailedRecords())))));
        Long transformerSuccess = defaultZero(firstNonNull(asLong(summaryMap.get("transformerSuccessRecords")), asLong(summaryMap.get("transformerSuccess"))));
        Long transformerFailed = defaultZero(firstNonNull(asLong(summaryMap.get("transformerFailedRecords")), asLong(summaryMap.get("transformerError"))));
        Long transformerFilter = defaultZero(firstNonNull(asLong(summaryMap.get("transformerFilterRecords")), asLong(summaryMap.get("transformerFilter"))));
        summary.setTransformerSuccessRecords(transformerSuccess);
        summary.setTransformerFailedRecords(transformerFailed);
        summary.setTransformerFilterRecords(transformerFilter);
        summary.setTransformerTotalRecords(defaultZero(firstNonNull(asLong(summaryMap.get("transformerTotalRecords")),
                transformerSuccess + transformerFailed + transformerFilter)));
        normalizeFailedTerminalMetrics(summary,
                isFailedStatus(payload == null ? null : payload.get("status"))
                        || isFailedStatus(summaryMap.get("jobState")));
        return summary;
    }

    private void normalizeFailedTerminalMetrics(RunMetricSummaryView summary, boolean failed) {
        if (!failed || summary == null) {
            return;
        }
        summary.setSuccessRecords(Long.valueOf(0L));
        summary.setWriteSucceedRecords(Long.valueOf(0L));
        summary.setFailedRecords(Long.valueOf(Math.max(
                safeValue(summary.getFailedRecords()),
                safeValue(summary.getCollectedRecords()))));
    }

    private boolean isFailedStatus(Object value) {
        if (value == null) {
            return false;
        }
        String status = String.valueOf(value).trim().toUpperCase(java.util.Locale.ROOT);
        return "FAILED".equals(status)
                || "ERROR".equals(status)
                || "KILLED".equals(status)
                || "STOPPED".equals(status)
                || "CANCELLED".equals(status)
                || "CANCELED".equals(status);
    }

    public boolean hasPreciseMetrics(RunRecordEntity entity) {
        if (entity == null) {
            return false;
        }
        return entity.getCollectedRecords() != null
                && entity.getReadSucceedRecords() != null
                && entity.getReadFailedRecords() != null
                && entity.getWriteSucceedRecords() != null
                && entity.getWriteFailedRecords() != null
                && entity.getFailedRecords() != null
                && entity.getTransformerTotalRecords() != null
                && entity.getTransformerSuccessRecords() != null
                && entity.getTransformerFailedRecords() != null
                && entity.getTransformerFilterRecords() != null;
    }

    private Map<String, Object> summaryMap(Map<String, Object> payload) {
        if (payload == null) {
            return new LinkedHashMap<String, Object>();
        }
        Object summary = payload.get("summary");
        Map<String, Object> copied = copyObjectMap(summary);
        if (copied != null) {
            return copied;
        }
        return new LinkedHashMap<String, Object>();
    }

    private Long extractMetric(Map<String, Object> resultJson, String... keys) {
        Map<String, Object> summaryMap = summaryMap(resultJson);
        for (String key : keys) {
            Long value = asLong(summaryMap.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (!text.isEmpty()) {
                try {
                    return Long.valueOf(text);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private Long firstNonNull(Long primary, Long secondary) {
        return primary != null ? primary : secondary;
    }

    private long safeValue(Long value) {
        return value == null ? 0L : value.longValue();
    }

    private Long defaultZero(Long value) {
        return value == null ? Long.valueOf(0L) : value;
    }

    private Long calculateSuccessRecords(Long readSucceedRecords, Long writeFailedRecords) {
        long successRecords = safeValue(readSucceedRecords) - safeValue(writeFailedRecords);
        return Long.valueOf(Math.max(0L, successRecords));
    }

    private Long calculateSuccessRecordsOrNull(Long readSucceedRecords, Long writeFailedRecords) {
        if (readSucceedRecords == null) {
            return null;
        }
        return calculateSuccessRecords(readSucceedRecords, writeFailedRecords);
    }

    private boolean hasAnyValue(Long... values) {
        if (values == null) {
            return false;
        }
        for (Long value : values) {
            if (value != null) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> copyObjectMap(Object candidate) {
        if (!(candidate instanceof Map<?, ?>)) {
            return null;
        }
        Map<?, ?> source = (Map<?, ?>) candidate;
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return copy;
    }
}
