package com.jdragon.studio.dto.model.dto;

import com.jdragon.studio.dto.model.DataModelDatasourceOptionView;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ModelDiscoveryOptionResult {
    private List<DataModelDatasourceOptionView> models = new ArrayList<DataModelDatasourceOptionView>();
    private String message;
    private long total;
    private int pageNo = 1;
    private int pageSize;
    private boolean hasMore;
}
