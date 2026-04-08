package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("studio_project")
public class ProjectEntity extends BaseTenantEntity {
    private String projectCode;
    private String projectName;
    private String description;
    private Integer enabled;
    private Integer defaultProject;
}
