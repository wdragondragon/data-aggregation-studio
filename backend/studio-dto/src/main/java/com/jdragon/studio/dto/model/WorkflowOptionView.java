package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class WorkflowOptionView {
    private Long id;
    private Long projectId;
    private Long runtimeClusterId;
    private String runtimeClusterName;
    private String name;
}
