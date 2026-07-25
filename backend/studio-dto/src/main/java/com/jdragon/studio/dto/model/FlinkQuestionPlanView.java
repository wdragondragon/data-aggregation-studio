package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "Natural-language question plan without data execution")
public class FlinkQuestionPlanView {
    private String question;
    private String sql;
    private Long runtimeClusterId;
    private List<Long> modelIds = new ArrayList<Long>();
    private List<String> warnings = new ArrayList<String>();
    private Integer maxRows;
    private Integer scanMaxRows;
}
