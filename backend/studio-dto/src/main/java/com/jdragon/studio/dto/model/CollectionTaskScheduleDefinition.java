package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Collection task schedule definition")
public class CollectionTaskScheduleDefinition {
    @Schema(description = "Cron expression")
    private String cronExpression;

    @Schema(description = "Whether the schedule is enabled")
    private Boolean enabled;

    @Schema(description = "Timezone")
    private String timezone;
}
