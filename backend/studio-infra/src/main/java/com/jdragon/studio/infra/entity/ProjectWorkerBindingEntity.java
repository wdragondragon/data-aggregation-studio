package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("studio_project_worker_binding")
public class ProjectWorkerBindingEntity extends BaseTenantEntity {
    private Long projectId;
    private String workerCode;
    private Integer enabled;
}
