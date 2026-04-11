package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RunMetricTrendSeriesView {
    private String key;
    private String name;
    private List<Long> data = new ArrayList<Long>();
}
