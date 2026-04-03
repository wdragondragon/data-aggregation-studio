package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_permission")
public class PermissionEntity extends BaseTenantEntity {
    private String code;
    private String name;
    private String httpMethod;
    private String pathPattern;
}
