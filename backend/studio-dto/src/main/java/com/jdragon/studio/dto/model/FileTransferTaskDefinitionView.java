package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class FileTransferTaskDefinitionView extends BaseDefinition {
    private String name;
    private String code;
    private String status;
    private Integer version;
    private Integer publishedVersion;
    private Long runtimeClusterId;
    private Long sourceRuntimeClusterId;
    private String sourceRuntimeClusterName;
    private Long sourceDatasourceId;
    private String sourceDatasourceName;
    private String sourceDatasourceType;
    private Long targetRuntimeClusterId;
    private String targetRuntimeClusterName;
    private Long targetDatasourceId;
    private String targetDatasourceName;
    private String targetDatasourceType;
    private Map<String, Object> selection = new LinkedHashMap<String, Object>();
    private Map<String, Object> mapping = new LinkedHashMap<String, Object>();
    private Map<String, Object> policy = new LinkedHashMap<String, Object>();
    private Map<String, Object> runtime = new LinkedHashMap<String, Object>();
    private FileTransferScheduleDefinition schedule = new FileTransferScheduleDefinition();
}
