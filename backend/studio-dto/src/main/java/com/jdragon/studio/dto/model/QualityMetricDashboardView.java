package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class QualityMetricDashboardView {
    private Map<String, Long> summaryMetrics = new LinkedHashMap<String, Long>();
    private List<QualityScoreTrendPoint> scoreTrend = new ArrayList<QualityScoreTrendPoint>();
    private List<Map<String, Object>> issueTrend = new ArrayList<Map<String, Object>>();
    private List<Map<String, Object>> dimensionDistribution = new ArrayList<Map<String, Object>>();
    private List<Map<String, Object>> coverageMatrix = new ArrayList<Map<String, Object>>();
    private List<QualityAssetRiskView> riskyAssets = new ArrayList<QualityAssetRiskView>();
    private List<Map<String, Object>> noisyTargets = new ArrayList<Map<String, Object>>();
}
