package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("so_pf_managed_file_audit")
public class ManagedFileAuditEntity extends BaseProjectTenantEntity {
    private Long fileId;
    private String action;
    private String outcome;
    private Long actorUserId;
    private String actorName;
    private String ownerType;
    private Long ownerId;
    private String fieldKey;
    private String detail;
}
