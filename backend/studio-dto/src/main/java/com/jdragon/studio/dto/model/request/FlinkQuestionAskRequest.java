package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Natural-language question request for Flink SQL")
public class FlinkQuestionAskRequest {
    @NotBlank
    @Schema(description = "Natural-language analytics question")
    private String question;

    @Schema(description = "Optional datasource filter")
    private List<Long> datasourceIds = new ArrayList<Long>();

    @Schema(description = "Optional model filter")
    private List<Long> modelIds = new ArrayList<Long>();

    @Schema(description = "Maximum rows returned to frontend")
    private Integer maxRows;

    @Schema(description = "Optional maximum rows scanned from each source; maxRows only limits returned rows")
    private Integer scanMaxRows;
}
