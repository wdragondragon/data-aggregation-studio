package com.jdragon.studio.dto.model.request;

import lombok.Data;

@Data
public class RuntimeClusterSaveRequest {
    private Long id;
    private String code;
    private String name;
    private Boolean enabled;
    private String version;
}
