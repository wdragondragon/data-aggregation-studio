package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.CollectionTaskSourceBinding;
import com.jdragon.studio.dto.model.CollectionTaskTargetBinding;
import com.jdragon.studio.dto.model.FieldMappingDefinition;
import com.jdragon.studio.dto.model.request.CollectionTaskSaveRequest;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskMetricBindingMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskScheduleMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.service.CollectionTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CollectionTaskFieldMappingValidationRegressionTest {

    @Test
    void shouldRejectIncompleteFieldMappingBeforePersistence() {
        CollectionTaskSaveRequest request = validRequest();
        request.getFieldMappings().add(new FieldMappingDefinition());

        assertThatThrownBy(() -> validate(request))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("Field mapping 2 requires a target field");
    }

    @Test
    void shouldAcceptExpressionOnlyMappingAndCompleteSourceMapping() {
        CollectionTaskSaveRequest request = validRequest();
        FieldMappingDefinition expression = new FieldMappingDefinition();
        expression.setTargetField("target_b");
        expression.setExpression("CURRENT_TIMESTAMP");
        request.getFieldMappings().add(expression);

        assertThatCode(() -> validate(request)).doesNotThrowAnyException();
    }

    private void validate(CollectionTaskSaveRequest request) {
        ReflectionTestUtils.invokeMethod(service(), "validateRequest", request);
    }

    private CollectionTaskService service() {
        return new CollectionTaskService(
                mock(CollectionTaskDefinitionMapper.class),
                mock(CollectionTaskMetricBindingMapper.class),
                mock(CollectionTaskScheduleMapper.class),
                mock(DispatchTaskMapper.class),
                mock(RunRecordMapper.class),
                null,
                null,
                null,
                new ObjectMapper(),
                null,
                null,
                null,
                null);
    }

    private CollectionTaskSaveRequest validRequest() {
        CollectionTaskSourceBinding source = new CollectionTaskSourceBinding();
        source.setDatasourceId(1L);
        source.setModelId(11L);
        source.setSourceAlias("src1");
        CollectionTaskTargetBinding target = new CollectionTaskTargetBinding();
        target.setDatasourceId(2L);
        target.setModelId(22L);
        FieldMappingDefinition mapping = new FieldMappingDefinition();
        mapping.setSourceAlias("src1");
        mapping.setSourceField("source_a");
        mapping.setTargetField("target_a");

        CollectionTaskSaveRequest request = new CollectionTaskSaveRequest();
        request.setName("field_mapping_validation");
        request.setSourceBindings(Collections.singletonList(source));
        request.setTargetBinding(target);
        request.setFieldMappings(new java.util.ArrayList<FieldMappingDefinition>(Collections.singletonList(mapping)));
        return request;
    }
}
