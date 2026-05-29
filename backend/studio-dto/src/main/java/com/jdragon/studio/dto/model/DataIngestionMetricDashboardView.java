package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DataIngestionMetricDashboardView {
    private DataIngestionMetricSummaryView summary = new DataIngestionMetricSummaryView();
    private RunMetricTrendView accessTrend = new RunMetricTrendView();
    private RunMetricTrendView cumulativeAccessTrend = new RunMetricTrendView();
    private RunMetricTrendView receivedTrend = new RunMetricTrendView();
    private RunMetricTrendView cumulativeReceivedTrend = new RunMetricTrendView();
    private RunMetricTrendView writtenTrend = new RunMetricTrendView();
    private RunMetricTrendView cumulativeWrittenTrend = new RunMetricTrendView();
    private RunMetricTrendView responseTimeTrend = new RunMetricTrendView();
    private RunMetricTrendView successRateTrend = new RunMetricTrendView();
    private List<DataServiceMetricDistributionView> errorDistribution = new ArrayList<DataServiceMetricDistributionView>();
    private List<DataIngestionApiMetricView> topSlowServices = new ArrayList<DataIngestionApiMetricView>();
    private List<DataIngestionApiMetricView> topFailedServices = new ArrayList<DataIngestionApiMetricView>();
    private List<DataIngestionApiMetricView> subscriptionRank = new ArrayList<DataIngestionApiMetricView>();
}
