package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.enums.RuntimeDatasourceProbeMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

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
    private List<String> physicalLocators = new ArrayList<String>();
    private DataModelDefinition model;
    private Integer limit;
    private String sql;
    private List<Object> parameters = new ArrayList<Object>();
    private Integer maxRows;
}
