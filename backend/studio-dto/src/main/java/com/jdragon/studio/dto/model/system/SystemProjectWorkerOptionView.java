package com.jdragon.studio.dto.model.system;

import lombok.Data;

@Data
public class SystemProjectWorkerOptionView {
    private String workerGroupCode;
    private String workerCode;
    private Integer onlineInstanceCount;
    private Integer recentInstanceCount;
    private Boolean boundToProject;
    private Boolean enabled;
}
