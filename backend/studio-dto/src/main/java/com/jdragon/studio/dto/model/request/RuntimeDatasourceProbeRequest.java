package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.enums.RuntimeDatasourceProbeMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Private control-plane to runtime request. It is authenticated by the internal runtime token. */
@Data
public class RuntimeDatasourceProbeRequest {
    @NotNull
    private Long targetClusterId;
    @NotBlank
    private String targetClusterCode;
    @NotBlank
    private String tenantId;
    @NotNull
    private Long projectId;
    private Long userId;
    private String username;
    @NotNull
    private RuntimeDatasourceProbeMode mode;
    @Valid
    @NotNull
    private DataSourceDefinition datasource;
    private String keyword;
    private Integer pageNo;
    private Integer pageSize;
    private String path;
    private String cursor;
    private String fileOperation;
    private String operationPath;
    private String operationTargetPath;
    private Boolean recursiveConfirmed;
    private Map<String, Object> fileTransferSpec = new LinkedHashMap<String, Object>();
    private Map<String, String> fileTransferParameters = new LinkedHashMap<String, String>();
    private Integer fileTransferPreviewLimit;
    private List<String> physicalLocators = new ArrayList<String>();
    private DataModelDefinition model;
    private Integer limit;
    private String sql;
    private List<Object> parameters = new ArrayList<Object>();
    private Integer maxRows;
}
