package com.jdragon.studio.dto.model.request;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DatasourceClusterBindingImpactRequest {
    private List<Long> applicableClusterIds = new ArrayList<Long>();
}
