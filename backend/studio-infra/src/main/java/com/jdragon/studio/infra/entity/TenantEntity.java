package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("studio_tenant")
public class TenantEntity extends BaseTenantEntity {
    private String tenantCode;
    private String tenantName;
    private String description;
    private Integer enabled;
}
