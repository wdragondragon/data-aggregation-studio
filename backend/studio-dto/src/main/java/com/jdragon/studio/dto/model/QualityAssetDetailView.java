package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class QualityAssetDetailView extends QualityAssetRiskView {
    private Map<String, Object> summaryMetrics = new LinkedHashMap<String, Object>();
    private List<QualityScoreTrendPoint> scoreTrend = new ArrayList<QualityScoreTrendPoint>();
    private List<QualityIssueView> activeIssues = new ArrayList<QualityIssueView>();
    private List<Map<String, Object>> relatedTasks = new ArrayList<Map<String, Object>>();
    private List<Map<String, Object>> latestFailures = new ArrayList<Map<String, Object>>();
    private List<Map<String, Object>> fieldIssueGroups = new ArrayList<Map<String, Object>>();
}
