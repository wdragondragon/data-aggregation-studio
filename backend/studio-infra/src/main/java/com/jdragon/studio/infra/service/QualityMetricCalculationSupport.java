package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.entity.QualityIssueEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class QualityMetricCalculationSupport {

    long executionHealth(List<RunRecordEntity> runRecords, List<QualityIssueEntity> activeIssues) {
        long totalRuns = runRecords == null ? 0L : runRecords.size();
        long failureRuns = 0L;
        long alertRuns = 0L;
        if (runRecords != null) {
            for (RunRecordEntity record : runRecords) {
                if ("FAILED".equalsIgnoreCase(record.getStatus())) {
                    failureRuns++;
                }
                if (isAlertRun(record)) {
                    alertRuns++;
                }
            }
        }
        long passRate = totalRuns <= 0L ? 100L : Math.max(0L, 100L - Math.round((failureRuns * 100.0d) / totalRuns));
        long alertFreeRate = totalRuns <= 0L ? 100L : Math.max(0L, 100L - Math.round((alertRuns * 100.0d) / totalRuns));
        long stability = Math.max(0L, 100L - Math.min(100L, penalty(activeIssues)));
        return Math.round(passRate * 0.45d + alertFreeRate * 0.30d + stability * 0.25d);
    }

    long governanceRisk(List<QualityIssueEntity> issues) {
        if (issues == null || issues.isEmpty()) {
            return 0L;
        }
        long weighted = 0L;
        long overdue = 0L;
        long ageHours = 0L;
        long reopen = 0L;
        for (QualityIssueEntity issue : issues) {
            weighted += severityWeight(issue.getSeverity());
            if (issue.getSlaDueAt() != null && issue.getSlaDueAt().isBefore(LocalDateTime.now())) {
                overdue++;
            }
            if (issue.getFirstSeenAt() != null) {
                ageHours += Math.max(0L, java.time.Duration.between(issue.getFirstSeenAt(), LocalDateTime.now()).toHours());
            }
            reopen += safeLong(issue.getReopenCount());
        }
        long weightedScore = Math.min(100L, weighted * 8L);
        long overdueScore = Math.min(100L, Math.round((overdue * 100.0d) / Math.max(1, issues.size())));
        long ageScore = Math.min(100L, Math.round((ageHours / Math.max(1.0d, issues.size())) / 72.0d * 100.0d));
        long reopenScore = Math.min(100L, Math.round((reopen * 100.0d) / Math.max(1, issues.size())));
        return Math.round(weightedScore * 0.50d + overdueScore * 0.25d + ageScore * 0.15d + reopenScore * 0.10d);
    }

    long coverageRate(int numerator, int denominator) {
        if (denominator <= 0) {
            return numerator <= 0 ? 0L : 100L;
        }
        return Math.round((numerator * 100.0d) / denominator);
    }

    long overdueCount(List<QualityIssueEntity> issues) {
        long total = 0L;
        for (QualityIssueEntity issue : issues) {
            if (issue.getSlaDueAt() != null && issue.getSlaDueAt().isBefore(LocalDateTime.now())) {
                total++;
            }
        }
        return total;
    }

    long affectedAssetCount(List<QualityIssueEntity> issues) {
        Set<String> assets = new LinkedHashSet<String>();
        for (QualityIssueEntity issue : issues) {
            assets.add(assetKey(issue.getDatasourceId(), issue.getModelId()));
        }
        return assets.size();
    }

    boolean matchesIssue(QualityIssueEntity issue, Long datasourceId, Long modelId, String ruleDimension, String granularity, Set<Long> taskIds) {
        if (issue == null) {
            return false;
        }
        if (datasourceId != null && !datasourceId.equals(issue.getDatasourceId())) {
            return false;
        }
        if (modelId != null && !modelId.equals(issue.getModelId())) {
            return false;
        }
        if (hasText(ruleDimension) && !safeText(issue.getRuleDimension(), "").equalsIgnoreCase(ruleDimension)) {
            return false;
        }
        if (hasText(granularity) && !safeText(issue.getGranularity(), "").equalsIgnoreCase(granularity)) {
            return false;
        }
        return taskIds != null && taskIds.contains(issue.getQualityTaskId());
    }

    QualityIssueEntity findIssue(List<QualityIssueEntity> issues, Long issueId) {
        for (QualityIssueEntity issue : issues) {
            if (issueId != null && issueId.equals(issue.getId())) {
                return issue;
            }
        }
        return null;
    }

    RunRecordEntity latestRecord(List<RunRecordEntity> records) {
        return records == null || records.isEmpty() ? null : records.get(0);
    }

    RunRecordEntity latestTaskRecord(List<RunRecordEntity> records, Long taskId) {
        for (RunRecordEntity record : records) {
            if (taskId != null && taskId.equals(record.getQualityTaskId())) {
                return record;
            }
        }
        return null;
    }

    QualityIssueEntity latestIssue(List<QualityIssueEntity> issues) {
        QualityIssueEntity latest = null;
        for (QualityIssueEntity issue : issues) {
            if (latest == null || compareTimeDesc(latest.getLastSeenAt(), issue.getLastSeenAt()) > 0) {
                latest = issue;
            }
        }
        return latest;
    }

    boolean isActive(QualityIssueEntity issue) {
        String status = issue == null ? null : issue.getStatus();
        return status != null && !"RESOLVED".equalsIgnoreCase(status) && !"FALSE_POSITIVE".equalsIgnoreCase(status);
    }

    boolean isAlertRun(RunRecordEntity record) {
        return safeLong(record == null || record.getResultJson() == null ? null : record.getResultJson().get("alertCount")) > 0L;
    }

    boolean isFailureOrAlert(RunRecordEntity record) {
        return record != null && ("FAILED".equalsIgnoreCase(record.getStatus()) || isAlertRun(record));
    }

    int normalizeTopN(Integer topN) {
        if (topN == null || topN.intValue() <= 0) {
            return 10;
        }
        return Math.min(50, topN.intValue());
    }

    Long parseAssetPart(String assetId, int index) {
        if (!hasText(assetId)) {
            return null;
        }
        String[] parts = assetId.split(":", -1);
        if (index >= parts.length || !hasText(parts[index])) {
            return null;
        }
        try {
            Long value = Long.valueOf(parts[index]);
            return value.longValue() <= 0L ? null : value;
        } catch (Exception ex) {
            return null;
        }
    }

    String assetKey(Long datasourceId, Long modelId) {
        return String.valueOf(datasourceId == null ? 0L : datasourceId) + ":" + String.valueOf(modelId == null ? 0L : modelId);
    }

    String severityKey(String severity) {
        String normalized = safeText(severity, "MEDIUM").toUpperCase(Locale.ROOT);
        if ("LOW".equals(normalized)) {
            return "Low";
        }
        if ("HIGH".equals(normalized)) {
            return "High";
        }
        if ("CRITICAL".equals(normalized)) {
            return "Critical";
        }
        return "Medium";
    }

    int compareTimeDesc(LocalDateTime left, LocalDateTime right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return right.compareTo(left);
    }

    long safeLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && ((String) value).trim().length() > 0) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (Exception ex) {
                return 0L;
            }
        }
        return 0L;
    }

    boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    String safeText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private long penalty(List<QualityIssueEntity> issues) {
        long total = 0L;
        for (QualityIssueEntity issue : issues) {
            total += safeLong(issue.getConsecutiveFailureCount()) * 8L;
            total += safeLong(issue.getReopenCount()) * 5L;
        }
        return total;
    }

    private long severityWeight(String severity) {
        String normalized = safeText(severity, "MEDIUM").toUpperCase(Locale.ROOT);
        if ("CRITICAL".equals(normalized)) {
            return 10L;
        }
        if ("HIGH".equals(normalized)) {
            return 6L;
        }
        if ("LOW".equals(normalized)) {
            return 1L;
        }
        return 3L;
    }
}
