package com.jdragon.studio.flink.service;

import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.FlinkQuestionResultView;
import com.jdragon.studio.dto.model.request.FlinkQuestionAskRequest;
import com.jdragon.studio.dto.model.request.FlinkSqlExecuteRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class FlinkQuestionService {
    private final FlinkQuestionContextService contextService;
    private final FlinkTextToSqlService textToSqlService;
    private final FlinkSqlExecutionService executionService;
    private final StudioPlatformProperties properties;

    public FlinkQuestionService(FlinkQuestionContextService contextService,
                                FlinkTextToSqlService textToSqlService,
                                FlinkSqlExecutionService executionService,
                                StudioPlatformProperties properties) {
        this.contextService = contextService;
        this.textToSqlService = textToSqlService;
        this.executionService = executionService;
        this.properties = properties;
    }

    public FlinkQuestionResultView ask(FlinkQuestionAskRequest request) {
        int maxRows = normalizeMaxRows(request.getMaxRows());
        FlinkQuestionContext context = contextService.build(request);
        List<String> warnings = new ArrayList<String>();
        String sql = textToSqlService.generateSql(request.getQuestion(), context, maxRows, warnings);
        FlinkSqlExecuteRequest executeRequest = new FlinkSqlExecuteRequest();
        executeRequest.setSql(sql);
        executeRequest.setMaxRows(maxRows);
        executeRequest.setScanMaxRows(request.getScanMaxRows());
        List<Long> modelIds = new ArrayList<Long>();
        for (DataModelDefinition model : context.getModels()) {
            modelIds.add(model.getId());
        }
        executeRequest.setModelIds(modelIds);
        FlinkQuestionResultView result = executionService.execute(executeRequest);
        result.setQuestion(request.getQuestion());
        result.getWarnings().addAll(warnings);
        return result;
    }

    private int normalizeMaxRows(Integer requested) {
        int configured = properties.getFlink().getMaxRows() == null ? 500 : properties.getFlink().getMaxRows();
        if (requested == null || requested <= 0) {
            return configured;
        }
        return Math.min(requested, configured);
    }
}
