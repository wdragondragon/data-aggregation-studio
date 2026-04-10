package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Model index rebuild queue status")
public class DataModelIndexQueueStatusView {

    @Schema(description = "Queued model rebuild count that has not entered active processing yet")
    private Integer queuedRebuildCount;

    @Schema(description = "Model rebuild count that is currently being processed")
    private Integer activeRebuildCount;

    @Schema(description = "Total pending rebuild count, including queued and active rebuilds")
    private Integer pendingRebuildCount;

    @Schema(description = "Queued command count, including rebuild and delete commands")
    private Integer queuedCommandCount;

    @Schema(description = "Active command count in the current worker batch")
    private Integer activeCommandCount;

    @Schema(description = "Whether the queue is busy")
    private Boolean busy;
}
