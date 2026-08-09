package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.model.FileTransferScheduleDefinition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class FileTransferTaskSaveRequest {
    private Long id;
    @NotBlank(message = "Task name is required")
    private String name;
    @NotBlank(message = "Task code is required")
    private String code;
    /** Preferred single-cluster request field. */
    private Long runtimeClusterId;
    /** @deprecated New requests use runtimeClusterId. */
    private Long sourceRuntimeClusterId;
    @NotNull(message = "Source datasource is required")
    private Long sourceDatasourceId;
    /** @deprecated New requests use runtimeClusterId. */
    private Long targetRuntimeClusterId;
    @NotNull(message = "Target datasource is required")
    private Long targetDatasourceId;
    private Map<String, Object> selection = new LinkedHashMap<String, Object>();
    private Map<String, Object> mapping = new LinkedHashMap<String, Object>();
    private Map<String, Object> policy = new LinkedHashMap<String, Object>();
    private Map<String, Object> runtime = new LinkedHashMap<String, Object>();
    private FileTransferScheduleDefinition schedule = new FileTransferScheduleDefinition();
}
