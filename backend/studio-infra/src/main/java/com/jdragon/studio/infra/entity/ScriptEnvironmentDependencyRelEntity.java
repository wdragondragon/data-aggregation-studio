package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("so_pf_env_dep_rel")
public class ScriptEnvironmentDependencyRelEntity extends BaseTenantEntity {
    private Long environmentId;
    private Long dependencyId;
    private Integer sortOrder;
}
