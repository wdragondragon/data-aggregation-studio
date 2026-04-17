package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.QualityRuleOutputType;
import com.jdragon.studio.dto.enums.QualityTaskAlertOperator;
import lombok.Data;

@Data
public class QualityTaskAlertConfig {
    private Integer outputOrder;
    private String resultField;
    private QualityRuleOutputType outputType;
    private Boolean enabled;
    private QualityTaskAlertOperator operator;
    private String expectedValue;
    private String minValue;
    private String maxValue;
}
