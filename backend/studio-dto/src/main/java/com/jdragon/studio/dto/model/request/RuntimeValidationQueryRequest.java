package com.jdragon.studio.dto.model.request;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RuntimeValidationQueryRequest {
    private String resourceType;
    private List<Long> resourceIds = new ArrayList<Long>();
}
