package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "Collection task incremental cursor state for one incremental field")
public class CollectionIncrementalCursorState {
    @Schema(description = "Incremental column")
    private String incrColumn;

    @Schema(description = "Incremental compare operator")
    private String incrModel;

    @Schema(description = "Current incremental cursor value")
    private Object pkValue;

    @Schema(description = "Incremental value type")
    private String valueType;

    @Schema(description = "Run record id that last updated this cursor")
    private Long lastRunRecordId;

    @Schema(description = "Cursor update time")
    private LocalDateTime lastUpdatedAt;
}
