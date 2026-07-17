package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
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
    private String mobilePhone;
    private Integer enabled;
    private String authSource;

    @TableField(exist = false)
    private String externalAccount;

    @TableField(exist = false)
    private String elinkUserId;

    @TableField(exist = false)
    private String elinkUserName;

    @TableField(exist = false)
    private Boolean clearElinkUserBinding;
}
