package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("studio_project_member_request")
public class ProjectMemberRequestEntity extends BaseTenantEntity {
    private Long projectId;
    private Long userId;
    private String requestType;
    private String status;
    private Long inviterUserId;
    private Long reviewerUserId;
    private String reason;
    private String reviewComment;
}
