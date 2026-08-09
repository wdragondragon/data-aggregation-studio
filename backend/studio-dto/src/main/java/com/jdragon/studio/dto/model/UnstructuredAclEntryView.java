package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class UnstructuredAclEntryView {
    private Long id;
    private Long datasourceId;
    private String path;
    private Boolean directory;
    private String principalType;
    private Long userId;
    private String username;
    private String displayName;
    private String permission;
    private String effect;
}
