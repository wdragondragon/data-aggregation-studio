package com.jdragon.studio.dto.model.request;

import lombok.Data;

@Data
public class DataIngestionResolveFieldsRequest {
    private Long datasourceId;
    private Long modelId;
}
