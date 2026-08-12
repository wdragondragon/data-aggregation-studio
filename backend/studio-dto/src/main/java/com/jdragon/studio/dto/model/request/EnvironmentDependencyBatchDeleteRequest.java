package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Environment dependency batch delete request")
public class EnvironmentDependencyBatchDeleteRequest {

    @Schema(description = "Dependency version ids")
    private List<Long> ids = new ArrayList<Long>();
}
