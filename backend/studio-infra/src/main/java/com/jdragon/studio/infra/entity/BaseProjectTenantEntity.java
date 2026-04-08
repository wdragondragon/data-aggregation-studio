package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class BaseProjectTenantEntity extends BaseTenantEntity {

    @TableField(fill = FieldFill.INSERT)
    private Long projectId;
}
