package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class StudioUserEntity extends BaseTenantEntity {
    private String username;
    private String passwordHash;
    private String displayName;
    private Integer enabled;
}
