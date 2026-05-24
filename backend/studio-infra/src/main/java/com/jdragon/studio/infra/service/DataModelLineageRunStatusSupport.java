package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.infra.entity.DataModelLineageRelationEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

final class DataModelLineageRunStatusSupport {

    static final String RUN_STATUS_NOT_RUN = "NOT_RUN";
    private static final String DISPLAY_STATUS_NOT_RUN = "NOT_RUN";
    private static final String DISPLAY_STATUS_RUNNING = "RUNNING";
    private static final String DISPLAY_STATUS_NORMAL = "NORMAL";
    private static final String DISPLAY_STATUS_EXCEPTION = "EXCEPTION";
    private static final String SOURCE_TYPE_MANUAL = "MANUAL";

    private DataModelLineageRunStatusSupport() {
    }

    static Comparator<DataModelLineageRelationEntity> runStatusComparator() {
        return new Comparator<DataModelLineageRelationEntity>() {
            @Override
            public int compare(DataModelLineageRelationEntity left, DataModelLineageRelationEntity right) {
                LocalDateTime leftTime = left == null ? null : left.getLatestRunAt();
                LocalDateTime rightTime = right == null ? null : right.getLatestRunAt();
                if (leftTime == null && rightTime == null) {
                    return compareNullableLong(right == null ? null : right.getLatestRunId(),
                            left == null ? null : left.getLatestRunId());
                }
                if (leftTime == null) {
                    return 1;
                }
                if (rightTime == null) {
                    return -1;
                }
                int compared = rightTime.compareTo(leftTime);
                if (compared != 0) {
                    return compared;
                }
                return compareNullableLong(right == null ? null : right.getLatestRunId(),
                        left == null ? null : left.getLatestRunId());
            }
        };
    }

    static String resolveDisplayStatus(DataModelLineageRelationEntity relation) {
        if (relation == null) {
            return DISPLAY_STATUS_NOT_RUN;
        }
        if (SOURCE_TYPE_MANUAL.equalsIgnoreCase(relation.getSourceType())) {
            return DISPLAY_STATUS_NORMAL;
        }
        String status = defaultRunStatus(relation.getLatestRunStatus());
        if ("RUNNING".equalsIgnoreCase(status)) {
            return DISPLAY_STATUS_RUNNING;
        }
        if ("SUCCESS".equalsIgnoreCase(status)) {
            return DISPLAY_STATUS_NORMAL;
        }
        if ("FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status)) {
            return DISPLAY_STATUS_EXCEPTION;
        }
        return DISPLAY_STATUS_NOT_RUN;
    }

    static boolean shouldUpdateRunStatus(DataModelLineageRelationEntity relation,
                                         ExecutionEvent event,
                                         LocalDateTime eventTime) {
        if (relation == null || event == null) {
            return false;
        }
        if (Objects.equals(relation.getLatestRunId(), event.getRunRecordId())) {
            return true;
        }
        if (relation.getLatestRunAt() == null) {
            return true;
        }
        return eventTime != null && !eventTime.isBefore(relation.getLatestRunAt());
    }

    static boolean shouldApplyLatestRun(DataModelLineageRelationEntity relation,
                                        RunRecordEntity run,
                                        LocalDateTime runTime) {
        if (relation == null || run == null) {
            return false;
        }
        if (Objects.equals(relation.getLatestRunId(), run.getId())
                && Objects.equals(defaultRunStatus(relation.getLatestRunStatus()), defaultRunStatus(run.getStatus()))
                && Objects.equals(relation.getLatestRunAt(), runTime)) {
            return false;
        }
        if (relation.getLatestRunAt() == null) {
            return true;
        }
        if (runTime == null) {
            return false;
        }
        return !runTime.isBefore(relation.getLatestRunAt());
    }

    static LocalDateTime resolveEventTime(ExecutionEvent event) {
        if (event == null) {
            return null;
        }
        if (event.getEndedAt() != null) {
            return event.getEndedAt();
        }
        if (event.getOccurredAt() != null) {
            return event.getOccurredAt();
        }
        return event.getStartedAt();
    }

    static LocalDateTime resolveRunTime(RunRecordEntity run) {
        if (run == null) {
            return null;
        }
        if (run.getEndedAt() != null) {
            return run.getEndedAt();
        }
        if (run.getStartedAt() != null) {
            return run.getStartedAt();
        }
        return run.getCreatedAt();
    }

    static String normalizeStatus(String status) {
        return DataModelLineageTextSupport.isBlank(status) ? null : status.trim().toUpperCase(Locale.ENGLISH);
    }

    static String defaultRunStatus(String status) {
        return DataModelLineageTextSupport.isBlank(status) ? RUN_STATUS_NOT_RUN : status;
    }

    private static int compareNullableLong(Long left, Long right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }
}
