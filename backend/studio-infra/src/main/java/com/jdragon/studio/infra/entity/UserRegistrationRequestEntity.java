package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_registration_request")
public class UserRegistrationRequestEntity extends BaseStudioEntity {
    private String status;
    private String username;
    private String passwordHash;
    private String displayName;
    private String reason;
    private String reviewComment;
    private Long reviewerUserId;
    private Long approvedUserId;
    private LocalDateTime reviewedAt;
}
