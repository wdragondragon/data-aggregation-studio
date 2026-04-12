package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class DataModelLineageEdgeDetailView {
    private String edgeId;
    private String sourceNodeTitle;
    private String targetNodeTitle;
    private String sourceField;
    private String targetField;
    private String sourceType;
    private String sourceTypeLabel;
    private String displayStatus;
    private String latestRunStatus;
    private Long latestRunId;
    private LocalDateTime latestRunAt;
    private List<DataModelLineageContributorView> contributors = new ArrayList<DataModelLineageContributorView>();
}
