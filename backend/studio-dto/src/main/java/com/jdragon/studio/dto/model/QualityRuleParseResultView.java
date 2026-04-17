package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class QualityRuleParseResultView {
    private List<QualityRuleInputParamView> inputParams = new ArrayList<QualityRuleInputParamView>();
    private List<QualityRuleOutputParamView> outputParams = new ArrayList<QualityRuleOutputParamView>();
    private List<String> warnings = new ArrayList<String>();
}
