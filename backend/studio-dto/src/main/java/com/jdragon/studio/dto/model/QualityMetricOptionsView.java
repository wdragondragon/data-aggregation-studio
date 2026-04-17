package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class QualityMetricOptionsView {
    private List<RunMetricFilterOptionView> datasources = new ArrayList<RunMetricFilterOptionView>();
    private List<RunMetricFilterOptionView> models = new ArrayList<RunMetricFilterOptionView>();
}
