package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Move request for data development directory or script")
public class DataDevelopmentMoveRequest {
    @Schema(description = "Target directory id, null means move to root")
    private Long targetDirectoryId;
}
