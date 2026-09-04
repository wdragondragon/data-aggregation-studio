package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ManagedFileReferenceView {
    private Long id;
    private Long fileId;
    private String ownerType;
    private Long ownerId;
    private String fieldKey;
    private Integer ordinal;
    private LocalDateTime createdAt;
}
