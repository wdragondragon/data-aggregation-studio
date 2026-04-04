package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RunListView {
    private List<QueuedTaskView> queuedTasks = new ArrayList<QueuedTaskView>();
    private List<RunRecordView> runRecords = new ArrayList<RunRecordView>();
}
