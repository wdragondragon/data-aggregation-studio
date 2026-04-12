package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DataModelLineageView {
    private Boolean editable;
    private DataModelLineageSummaryView summary;
    private List<DataModelLineageNodeView> nodes = new ArrayList<DataModelLineageNodeView>();
    private List<DataModelLineageEdgeView> edges = new ArrayList<DataModelLineageEdgeView>();
    private List<DataModelLineageUnresolvedExpressionView> unresolvedExpressions = new ArrayList<DataModelLineageUnresolvedExpressionView>();
}
