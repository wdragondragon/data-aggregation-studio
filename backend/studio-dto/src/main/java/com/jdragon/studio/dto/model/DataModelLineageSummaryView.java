package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class DataModelLineageSummaryView {
    private Integer upstreamDepth;
    private Integer totalUpstreamCount;
    private Integer directUpstreamCount;
    private Integer downstreamDepth;
    private Integer totalDownstreamCount;
    private Integer directDownstreamCount;
}
