package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RunListView {
    private List<QueuedTaskListView> queuedTasks = new ArrayList<QueuedTaskListView>();
    private List<RunRecordListView> runRecords = new ArrayList<RunRecordListView>();
}
