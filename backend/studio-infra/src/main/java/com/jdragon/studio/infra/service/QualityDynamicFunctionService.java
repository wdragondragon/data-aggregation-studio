package com.jdragon.studio.infra.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class QualityDynamicFunctionService {

    public String replaceAll(String content) {
        return DynamicFunctionEvaluator.replaceAll(content);
    }

    public String replaceAll(String content, LocalDateTime baseTime) {
        return DynamicFunctionEvaluator.replaceAll(content, baseTime);
    }

    public List<String> validate(String content) {
        return DynamicFunctionEvaluator.validate(content);
    }
}
