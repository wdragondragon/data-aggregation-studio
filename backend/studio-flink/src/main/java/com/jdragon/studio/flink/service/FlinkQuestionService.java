package com.jdragon.studio.flink.service;

import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.FlinkQuestionPlanView;
import com.jdragon.studio.dto.model.request.FlinkQuestionAskRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class FlinkQuestionService {
    private final FlinkQuestionContextService contextService;
    private final FlinkTextToSqlService textToSqlService;
    private final StudioPlatformProperties properties;

    public FlinkQuestionService(FlinkQuestionContextService contextService,
                                FlinkTextToSqlService textToSqlService,
                                StudioPlatformProperties properties) {
        this.contextService = contextService;
        this.textToSqlService = textToSqlService;
        this.properties = properties;
    }

    /**
     * Builds the model context and generates guarded SQL without reading any
     * business datasource. studio-server uses this control-plane step before
     * dispatching the generated SQL to the selected Worker cluster.
     */
    public FlinkQuestionPlanView plan(FlinkQuestionAskRequest request) {
        int maxRows = normalizeMaxRows(request.getMaxRows());
        FlinkQuestionContext context = contextService.build(request);
        List<String> warnings = new ArrayList<String>();
        String sql = textToSqlService.generateSql(request.getQuestion(), context, maxRows, warnings);
        FlinkQuestionPlanView plan = new FlinkQuestionPlanView();
        plan.setQuestion(request.getQuestion());
        plan.setSql(sql);
        plan.setRuntimeClusterId(request.getRuntimeClusterId());
        plan.setMaxRows(maxRows);
        plan.setScanMaxRows(request.getScanMaxRows());
        List<Long> modelIds = new ArrayList<Long>();
        for (DataModelDefinition model : context.getModels()) {
            modelIds.add(model.getId());
        }
        plan.setModelIds(modelIds);
        plan.setWarnings(warnings);
        return plan;
    }

    private int normalizeMaxRows(Integer requested) {
        int configured = properties.getFlink().getMaxRows() == null ? 500 : properties.getFlink().getMaxRows();
        if (requested == null || requested <= 0) {
            return configured;
        }
        return Math.min(requested, configured);
    }
}
