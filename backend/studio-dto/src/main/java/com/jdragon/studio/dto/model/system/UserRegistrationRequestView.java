package com.jdragon.studio.dto.model.system;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserRegistrationRequestView {
    private Long id;
    private String status;
    private String username;
    private String displayName;
    private String reason;
    private String reviewComment;
    private Long reviewerUserId;
    private String reviewerUsername;
    private Long approvedUserId;
    private String approvedUsername;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime reviewedAt;
}
