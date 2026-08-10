package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class EnvironmentDependencyListView extends BaseDefinition {
    private String name;
    private String version;
    private String scriptType;
    private Long artifactStoreId;
    private Boolean enabled;
    private List<EnvironmentDependencyFileListView> files = new ArrayList<EnvironmentDependencyFileListView>();
}
