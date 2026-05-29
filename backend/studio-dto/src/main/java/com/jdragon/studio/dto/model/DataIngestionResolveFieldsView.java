package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DataIngestionResolveFieldsView {
    private List<DataServiceFieldView> fields = new ArrayList<DataServiceFieldView>();
    private List<DataIngestionFieldMapping> fieldMappings = new ArrayList<DataIngestionFieldMapping>();
}
