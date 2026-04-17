package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.QualityRuleParamType;
import lombok.Data;

@Data
public class QualityTaskParamBinding {
    private Integer paramOrder;
    private String paramName;
    private QualityRuleParamType paramType;
    private String paramMeaning;
    private String paramValue;
    private Boolean editable;
}
