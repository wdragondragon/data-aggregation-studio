package com.jdragon.studio.infra.service;

import lombok.Data;

@Data
public class DataModelMatchUnit {
    private Long modelId;
    private String itemKey;

    public DataModelMatchUnit() {
    }

    public DataModelMatchUnit(Long modelId, String itemKey) {
        this.modelId = modelId;
        this.itemKey = itemKey;
    }
}
