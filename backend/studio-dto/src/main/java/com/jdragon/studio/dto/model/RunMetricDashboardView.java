package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RunMetricDashboardView {
    private RunMetricTrendView trend = new RunMetricTrendView();
    private List<RunMetricTopNItemView> sourceDatasourceTopN = new ArrayList<RunMetricTopNItemView>();
    private List<RunMetricTopNItemView> targetDatasourceTopN = new ArrayList<RunMetricTopNItemView>();
    private List<RunMetricTopNItemView> sourceModelTopN = new ArrayList<RunMetricTopNItemView>();
    private List<RunMetricTopNItemView> targetModelTopN = new ArrayList<RunMetricTopNItemView>();
    private Long legacyRunCount;
}
