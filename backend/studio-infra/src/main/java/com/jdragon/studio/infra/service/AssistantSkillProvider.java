package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.assistant.AssistantPlanRequest;

import java.util.List;
import java.util.Map;

public interface AssistantSkillProvider {

    List<Map<String, Object>> assistantSkills(AssistantPlanRequest request);
}

