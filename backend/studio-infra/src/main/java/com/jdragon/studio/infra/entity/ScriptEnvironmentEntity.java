package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("so_pf_script_env")
public class ScriptEnvironmentEntity extends BaseTenantEntity {
    private String environmentName;
    private String environmentCode;
    private Integer enabled;
    private Integer useApplicationParent;
    private Long environmentVersion;
    private String description;
}
