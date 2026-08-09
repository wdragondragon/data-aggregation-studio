package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "file_transfer_task_definition", autoResultMap = true)
public class FileTransferTaskDefinitionEntity extends BaseProjectTenantEntity {
    private Long createdBy;
    private String name;
    private String code;
    private String status;
    private Integer version;
    private Integer publishedVersion;
    private Long runtimeClusterId;
    private Long sourceRuntimeClusterId;
    private Long sourceDatasourceId;
    private String sourceDatasourceNameSnapshot;
    private String sourceDatasourceTypeSnapshot;
    private Long targetRuntimeClusterId;
    private Long targetDatasourceId;
    private String targetDatasourceNameSnapshot;
    private String targetDatasourceTypeSnapshot;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> selectionJson = new LinkedHashMap<String, Object>();
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> mappingJson = new LinkedHashMap<String, Object>();
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> policyJson = new LinkedHashMap<String, Object>();
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> runtimeJson = new LinkedHashMap<String, Object>();
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> publishedSnapshotJson = new LinkedHashMap<String, Object>();

    private Integer scheduleEnabled;
    private String cronExpression;
    private String timezone;
    private LocalDateTime lastTriggeredAt;
}
