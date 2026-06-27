package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.RunMetricSummaryView;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class RunMetricBackfillService {

    private final RunRecordMapper runRecordMapper;
    private final RunMetricSummaryMapper runMetricSummaryMapper;
    private final ObjectMapper objectMapper;

    public RunMetricBackfillService(RunRecordMapper runRecordMapper,
                                    RunMetricSummaryMapper runMetricSummaryMapper,
                                    ObjectMapper objectMapper) {
        this.runRecordMapper = runRecordMapper;
        this.runMetricSummaryMapper = runMetricSummaryMapper;
        this.objectMapper = objectMapper;
    }

    public RunMetricBackfillResult backfillSuccessMetrics() {
        List<RunRecordEntity> records = runRecordMapper.selectList(new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getDeleted, 0)
                .isNotNull(RunRecordEntity::getCollectionTaskId)
                .orderByAsc(RunRecordEntity::getId));
        long scanned = 0L;
        long updated = 0L;
        for (RunRecordEntity record : records) {
            scanned++;
            RunMetricSummaryView summary = runMetricSummaryMapper.fromEntity(record);
            Long successRecords = summary == null ? Long.valueOf(0L) : summary.getSuccessRecords();
            boolean changed = false;
            changed |= applyMetricColumns(record, summary);
            Map<String, Object> payloadJson = mutableMap(record.getPayloadJson());
            Map<String, Object> resultJson = mutableMap(record.getResultJson());
            changed |= applySuccessRecords(payloadJson, successRecords);
            changed |= applySuccessRecords(resultJson, successRecords);
            if (!changed) {
                continue;
            }
            record.setPayloadJson(payloadJson);
            record.setResultJson(resultJson);
            runRecordMapper.updateById(record);
            updated++;
        }
        return new RunMetricBackfillResult(scanned, updated);
    }

    private boolean applyMetricColumns(RunRecordEntity record, RunMetricSummaryView summary) {
        if (record == null || summary == null) {
            return false;
        }
        boolean changed = false;
        changed |= assignMetric(record.getCollectedRecords(), summary.getCollectedRecords(), record::setCollectedRecords);
        changed |= assignMetric(record.getReadSucceedRecords(), summary.getReadSucceedRecords(), record::setReadSucceedRecords);
        changed |= assignMetric(record.getReadFailedRecords(), summary.getReadFailedRecords(), record::setReadFailedRecords);
        changed |= assignMetric(record.getWriteSucceedRecords(), summary.getWriteSucceedRecords(), record::setWriteSucceedRecords);
        changed |= assignMetric(record.getWriteFailedRecords(), summary.getWriteFailedRecords(), record::setWriteFailedRecords);
        changed |= assignMetric(record.getFailedRecords(), summary.getFailedRecords(), record::setFailedRecords);
        changed |= assignMetric(record.getSuccessRecords(), summary.getSuccessRecords(), record::setSuccessRecords);
        changed |= assignMetric(record.getTransformerTotalRecords(), summary.getTransformerTotalRecords(), record::setTransformerTotalRecords);
        changed |= assignMetric(record.getTransformerSuccessRecords(), summary.getTransformerSuccessRecords(), record::setTransformerSuccessRecords);
        changed |= assignMetric(record.getTransformerFailedRecords(), summary.getTransformerFailedRecords(), record::setTransformerFailedRecords);
        changed |= assignMetric(record.getTransformerFilterRecords(), summary.getTransformerFilterRecords(), record::setTransformerFilterRecords);
        return changed;
    }

    private boolean applySuccessRecords(Map<String, Object> root, Long successRecords) {
        if (root == null) {
            return false;
        }
        Object summaryValue = root.get("summary");
        Map<String, Object> summary = copyObjectMap(summaryValue);
        if (summary == null) {
            summary = new LinkedHashMap<String, Object>();
        }
        if (Objects.equals(asLong(summary.get("successRecords")), successRecords)) {
            return false;
        }
        summary.put("successRecords", successRecords == null ? Long.valueOf(0L) : successRecords);
        root.put("summary", summary);
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        return objectMapper.convertValue(source, LinkedHashMap.class);
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

    private boolean assignMetric(Long currentValue, Long nextValue, java.util.function.Consumer<Long> setter) {
        Long normalizedNext = nextValue == null ? Long.valueOf(0L) : nextValue;
        if (Objects.equals(currentValue, normalizedNext)) {
            return false;
        }
        setter.accept(normalizedNext);
        return true;
    }

    public static final class RunMetricBackfillResult {
        private final long scannedCount;
        private final long updatedCount;

        public RunMetricBackfillResult(long scannedCount, long updatedCount) {
            this.scannedCount = scannedCount;
            this.updatedCount = updatedCount;
        }

        public long getScannedCount() {
            return scannedCount;
        }

        public long getUpdatedCount() {
            return updatedCount;
        }
    }
}
