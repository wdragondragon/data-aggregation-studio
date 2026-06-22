package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.mapper.WorkflowEdgeMapper;
import com.jdragon.studio.infra.mapper.WorkflowNodeMapper;
import com.jdragon.studio.infra.mapper.WorkflowScheduleMapper;
import com.jdragon.studio.infra.mapper.WorkflowVersionMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowServiceRegressionTest {

    @Test
    void getShouldRejectMissingWorkflowInsteadOfReturningNull() {
        WorkflowDefinitionMapper definitionMapper = mock(WorkflowDefinitionMapper.class);
        when(definitionMapper.selectById(999L)).thenReturn(null);
        WorkflowService workflowService = workflowService(definitionMapper);

        StudioException exception = assertThrows(StudioException.class, () -> workflowService.get(999L));

        assertEquals(StudioErrorCode.NOT_FOUND, exception.getCode());
        assertEquals("Workflow not found: 999", exception.getMessage());
    }

    private WorkflowService workflowService(WorkflowDefinitionMapper definitionMapper) {
        return new WorkflowService(
                definitionMapper,
                mock(WorkflowVersionMapper.class),
                mock(WorkflowNodeMapper.class),
                mock(WorkflowEdgeMapper.class),
                mock(WorkflowScheduleMapper.class),
                mock(DispatchTaskMapper.class),
                mock(RunRecordMapper.class),
                mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class));
    }
}
