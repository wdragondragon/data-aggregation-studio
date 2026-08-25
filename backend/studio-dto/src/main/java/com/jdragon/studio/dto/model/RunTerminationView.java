package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class RunTerminationView {
    private Long dispatchTaskId;
    private Long runRecordId;
    private Long collectionTaskId;
    private String status;
    private boolean changed;
    private boolean terminationRequested;
    private String message;
}
