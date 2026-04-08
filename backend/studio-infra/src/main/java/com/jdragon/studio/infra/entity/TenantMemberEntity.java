package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("studio_tenant_member")
public class TenantMemberEntity extends BaseTenantEntity {
    private Long userId;
    private String roleCode;
    private String status;
}
