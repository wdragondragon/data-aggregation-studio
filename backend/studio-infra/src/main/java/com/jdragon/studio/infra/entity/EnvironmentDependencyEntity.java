package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("so_pf_env_dep")
public class EnvironmentDependencyEntity extends BaseTenantEntity {
    private String name;
    private String version;
    private String artifactUrl;
    private String artifactType;
    private String checksum;
    private Integer enabled;
    private String description;
}
