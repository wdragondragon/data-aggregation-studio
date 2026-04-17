package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DataServiceMetricDashboardView {
    private DataServiceMetricSummaryView summary = new DataServiceMetricSummaryView();
    private RunMetricTrendView accessTrend = new RunMetricTrendView();
    private RunMetricTrendView cumulativeAccessTrend = new RunMetricTrendView();
    private RunMetricTrendView outputRowTrend = new RunMetricTrendView();
    private RunMetricTrendView cumulativeOutputRowTrend = new RunMetricTrendView();
    private RunMetricTrendView cacheHitTrend = new RunMetricTrendView();
    private RunMetricTrendView cumulativeCacheHitTrend = new RunMetricTrendView();
    private RunMetricTrendView responseTimeTrend = new RunMetricTrendView();
    private RunMetricTrendView successRateTrend = new RunMetricTrendView();
    private List<DataServiceMetricDistributionView> errorDistribution = new ArrayList<DataServiceMetricDistributionView>();
    private List<DataServiceApiMetricView> topSlowApis = new ArrayList<DataServiceApiMetricView>();
    private List<DataServiceApiMetricView> topFailedApis = new ArrayList<DataServiceApiMetricView>();
    private List<DataServiceApiMetricView> subscriptionRank = new ArrayList<DataServiceApiMetricView>();
}
