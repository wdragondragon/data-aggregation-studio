package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ManagedFileView {
    private Long id;
    private String fileName;
    private String policyCode;
    private String contentType;
    private Long sizeBytes;
    private String sha256;
    private String sha256Summary;
    private String status;
    private LocalDateTime uploadedAt;
    private LocalDateTime expiresAt;
    private Long uploadedBy;
    private Long referenceCount;
    private Boolean referenced;
    private Boolean downloadable;
    private Boolean deletable;
    private String errorMessage;
}
