package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("studio_resource_share")
public class ResourceShareEntity extends BaseTenantEntity {
    private Long sourceProjectId;
    private Long targetProjectId;
    private String resourceType;
    private Long resourceId;
    private Long sharedByUserId;
    private Integer enabled;
}
