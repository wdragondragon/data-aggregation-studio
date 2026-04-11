package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RunMetricTrendView {
    private List<String> xAxis = new ArrayList<String>();
    private List<RunMetricTrendSeriesView> series = new ArrayList<RunMetricTrendSeriesView>();
}
