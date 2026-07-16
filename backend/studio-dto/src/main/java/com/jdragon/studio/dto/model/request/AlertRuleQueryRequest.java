package com.jdragon.studio.dto.model.request;

import lombok.Data;

@Data
public class AlertRuleQueryRequest {
    private String keyword;
    private String ruleType;
    private String subjectType;
    private String severity;
    private Boolean enabled;
    private Integer pageNo;
    private Integer pageSize;
}
