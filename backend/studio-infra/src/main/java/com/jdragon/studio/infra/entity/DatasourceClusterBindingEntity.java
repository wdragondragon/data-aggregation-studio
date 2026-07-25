package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * User-configured datasource reachability. This is deliberately independent
 * from connection health: an unavailable probe must not remove applicability.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("datasource_cluster_binding")
public class DatasourceClusterBindingEntity extends BaseTenantEntity {
    private Long datasourceId;
    private Long runtimeClusterId;
    private Integer enabled;
}
