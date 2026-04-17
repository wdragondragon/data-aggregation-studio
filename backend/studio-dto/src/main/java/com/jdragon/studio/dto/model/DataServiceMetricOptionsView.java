package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DataServiceMetricOptionsView {
    private List<DataServiceMetricOptionView> services = new ArrayList<DataServiceMetricOptionView>();
    private List<DataServiceMetricOptionView> subscriptions = new ArrayList<DataServiceMetricOptionView>();
}
