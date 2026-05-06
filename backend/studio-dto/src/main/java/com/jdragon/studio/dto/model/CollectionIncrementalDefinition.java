package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;

@Data
@Schema(description = "Collection task incremental cursor definition")
public class CollectionIncrementalDefinition {
    @Schema(description = "Whether incremental collection is enabled for this source")
    private Boolean enabled;

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

    @Schema(description = "System maintained cursor states by incremental field and operator")
    private List<CollectionIncrementalCursorState> cursorStates = new ArrayList<CollectionIncrementalCursorState>();
}
