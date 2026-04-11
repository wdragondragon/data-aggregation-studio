package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RunMetricOptionsView {
    private List<RunMetricFilterOptionView> datasources = new ArrayList<RunMetricFilterOptionView>();
    private List<RunMetricFilterOptionView> sourceModels = new ArrayList<RunMetricFilterOptionView>();
    private List<RunMetricFilterOptionView> targetModels = new ArrayList<RunMetricFilterOptionView>();
}
