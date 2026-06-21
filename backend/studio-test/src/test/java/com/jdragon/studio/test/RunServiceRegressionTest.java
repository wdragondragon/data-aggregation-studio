package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.dto.model.RunRecordView;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.RunMetricSummaryMapper;
import com.jdragon.studio.infra.service.RunService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunServiceRegressionTest {

    @Test
    void shouldSanitizeHistoricalRunRecordViewAndFallbackLog() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        RunRecordEntity entity = new RunRecordEntity();
        entity.setId(200L);
        entity.setTenantId("default");
        entity.setProjectId(300L);
        entity.setStatus("FAILED");
        entity.setMessage("java.sql.SQLSyntaxErrorException: Unknown column 'contract_amount' in 'field list'");
        entity.setPayloadJson(payload("java.sql.SQLException: Field 'audit_required' doesn't have a default value\n"
                + "\tat com.jdragon.aggregation.rdbms.writer.CommonRdbmsWriter.doOneInsert(CommonRdbmsWriter.java:530)"));
        entity.setResultJson(payload("COLLECTION_TASK node failed in 8 ms (mysql -> mysql): "
                + "java.sql.SQLSyntaxErrorException: Unknown column 'contract_amount' in 'field list'"));
        when(runRecordMapper.selectById(eq(200L))).thenReturn(entity);

        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        when(collectionTaskService.list(null, null, null)).thenReturn(Collections.emptyList());
        QualityTaskService qualityTaskService = mock(QualityTaskService.class);
        when(qualityTaskService.list(null, null, null, null)).thenReturn(Collections.emptyList());
        WorkflowDefinitionMapper workflowDefinitionMapper = mock(WorkflowDefinitionMapper.class);
        when(workflowDefinitionMapper.selectList(any(Wrapper.class))).thenReturn(Collections.<WorkflowDefinitionEntity>emptyList());
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentProjectId()).thenReturn(300L);
        RunService service = new RunService(
                mock(DispatchTaskMapper.class),
                runRecordMapper,
                collectionTaskService,
                qualityTaskService,
                workflowDefinitionMapper,
                securityService,
                mock(RunMetricSummaryMapper.class));

        RunRecordView view = service.get(200L);
        assertEquals("Unknown column 'contract_amount' in 'field list'", view.getMessage());
        assertEquals("Field 'audit_required' doesn't have a default value", view.getPayloadJson().get("error"));
        assertEquals("Unknown column 'contract_amount' in 'field list'", view.getResultJson().get("message"));
        assertCleanFailureText(String.valueOf(view.getPayloadJson()));
        assertCleanFailureText(String.valueOf(view.getResultJson()));

        RunLogView fallback = service.buildHistoricalFallback(entity);
        assertCleanFailureText(fallback.getContent());
    }

    private Map<String, Object> payload(String message) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("message", message);
        result.put("error", message);
        result.put("exceptionType", "java.sql.SQLException");
        return result;
    }

    private void assertCleanFailureText(String message) {
        assertFalse(message.contains("java."));
        assertFalse(message.contains("com.jdragon"));
        assertFalse(message.contains(".java:"));
        assertFalse(message.contains("\tat "));
    }
}
