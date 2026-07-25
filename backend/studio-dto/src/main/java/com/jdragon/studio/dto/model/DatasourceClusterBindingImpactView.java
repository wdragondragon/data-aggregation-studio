package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DatasourceClusterBindingImpactView {
    private Long datasourceId;
    private List<Long> removedClusterIds = new ArrayList<Long>();
    private List<RuntimeValidationView> affectedResources = new ArrayList<RuntimeValidationView>();
}
