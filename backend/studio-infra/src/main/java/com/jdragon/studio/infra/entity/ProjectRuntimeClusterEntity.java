package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("studio_project_runtime_cluster")
public class ProjectRuntimeClusterEntity extends BaseProjectTenantEntity {
    private Long runtimeClusterId;
    private Integer enabled;
    private Integer preferred;
    private Integer allowManualOverride;
}
