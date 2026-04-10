package com.jdragon.studio.dto.model.dto;

import com.jdragon.studio.dto.model.DataModelDefinition;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ModelDiscoveryResult {
    private List<DataModelDefinition> models = new ArrayList<DataModelDefinition>();
    private String message;
    private long total;
    private int pageNo = 1;
    private int pageSize;
    private boolean hasMore;
}

