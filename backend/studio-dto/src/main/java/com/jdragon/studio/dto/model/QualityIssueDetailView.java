package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class QualityIssueDetailView extends QualityIssueView {
    private Map<String, Object> currentEvidence = new LinkedHashMap<String, Object>();
    private List<QualityIssueTimelineEvent> timeline = new ArrayList<QualityIssueTimelineEvent>();
}
