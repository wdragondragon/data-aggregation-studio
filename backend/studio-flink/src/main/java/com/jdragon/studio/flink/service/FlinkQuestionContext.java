package com.jdragon.studio.flink.service;

import com.jdragon.studio.dto.model.DataModelDefinition;

import java.util.ArrayList;
import java.util.List;

class FlinkQuestionContext {
    private final List<DataModelDefinition> models = new ArrayList<DataModelDefinition>();
    private String promptContext;

    List<DataModelDefinition> getModels() {
        return models;
    }

    String getPromptContext() {
        return promptContext;
    }

    void setPromptContext(String promptContext) {
        this.promptContext = promptContext;
    }
}
