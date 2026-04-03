package com.jdragon.studio.core.spi;

import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;

import java.util.List;
import java.util.Map;

public interface ModelDiscoveryProvider {
    boolean supports(String typeCode);

    List<Map<String, Object>> preview(DataSourceDefinition datasource, DataModelDefinition model, int limit);
}

