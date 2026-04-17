package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class QualityRuleValidationResultView {
    private Boolean valid;
    private String message;
    private List<String> warnings = new ArrayList<String>();
}
