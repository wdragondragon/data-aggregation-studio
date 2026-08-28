package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.StreamingDesiredState;
import com.jdragon.studio.dto.enums.StreamingObservedState;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class StreamingTaskDeploymentView {
    private Long id;
    private Long collectionTaskId;
    private Long runtimeClusterId;
    private Long generation;
    private StreamingDesiredState desiredState;
    private StreamingObservedState observedState;
    private Long currentRunId;
    private Long currentAttemptId;
    private Integer consecutiveFailureCount;
    private LocalDateTime nextRetryAt;
    private Map<String, Object> lastCheckpoint = new LinkedHashMap<String, Object>();
    private LocalDateTime lastCheckpointAt;
    private String lastErrorCode;
    private String lastErrorSummary;
    private LocalDateTime updatedAt;
}
