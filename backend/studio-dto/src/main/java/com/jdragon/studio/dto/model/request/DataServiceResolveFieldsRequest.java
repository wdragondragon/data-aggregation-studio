package com.jdragon.studio.dto.model.request;

import com.jdragon.studio.dto.enums.DataServiceSourceType;
import lombok.Data;

@Data
public class DataServiceResolveFieldsRequest {
    private DataServiceSourceType sourceType;
    private Long datasourceId;
    private Long modelId;
    private String customSql;
}
