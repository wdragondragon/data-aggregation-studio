package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CollectionTaskStreamingRuntimeView {
    private Long collectionTaskId;
    private String taskName;
    private StreamingTaskDeploymentView deployment;
    private StreamingTaskRunView currentRun;
    private StreamingTaskAttemptView currentAttempt;
    private List<StreamingTaskAttemptView> recentAttempts = new ArrayList<StreamingTaskAttemptView>();
}
