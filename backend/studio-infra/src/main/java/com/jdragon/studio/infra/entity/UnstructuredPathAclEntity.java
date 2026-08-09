package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("unstructured_path_acl")
public class UnstructuredPathAclEntity extends BaseProjectTenantEntity {
    private Long datasourceId;
    private String path;
    private Integer directory;
    private String principalType;
    private Long userId;
    private String permission;
    private String effect;
    private Long createdBy;
}
