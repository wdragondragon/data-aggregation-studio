package com.jdragon.studio.core.spi;

import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryResult;

public interface SourceCapabilityProvider {
    boolean supports(String typeCode);

    ConnectionTestResult testConnection(DataSourceDefinition definition);

    ModelDiscoveryResult discoverModels(DataSourceDefinition definition);
}

