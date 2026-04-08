package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("studio_project_member")
public class ProjectMemberEntity extends BaseTenantEntity {
    private Long projectId;
    private Long userId;
    private String roleCode;
    private String status;
}
