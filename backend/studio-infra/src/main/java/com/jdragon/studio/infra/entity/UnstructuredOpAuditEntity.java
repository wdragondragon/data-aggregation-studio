package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("unstructured_op_audit")
public class UnstructuredOpAuditEntity extends BaseProjectTenantEntity {
    private Long datasourceId;
    private Long runtimeClusterId;
    private Long userId;
    private String username;
    private String operation;
    private String sourcePath;
    private String targetPath;
    @TableField("`recursive`")
    private Integer recursive;
    private String status;
    private String message;
}
