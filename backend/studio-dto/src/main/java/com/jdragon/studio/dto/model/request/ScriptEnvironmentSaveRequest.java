package com.jdragon.studio.dto.model.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Script environment save request")
public class ScriptEnvironmentSaveRequest {
    private Long id;

    @NotBlank(message = "Environment name is required")
    private String environmentName;

    @NotBlank(message = "Environment code is required")
    private String environmentCode;

    private Boolean enabled;
    private Boolean useApplicationParent;
    private String pythonInstallMode;
    private Long pythonRepositoryId;
    private String description;
    private List<Long> dependencyIds = new ArrayList<Long>();
}
