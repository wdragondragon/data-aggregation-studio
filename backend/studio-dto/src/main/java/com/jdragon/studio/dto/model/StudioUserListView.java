package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class StudioUserListView {
    private Long id;
    private String tenantId;
    private Boolean deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String username;
    private String displayName;
    private String mobilePhone;
    private Integer enabled;
    private String elinkUserId;
    private String elinkUserName;
}
