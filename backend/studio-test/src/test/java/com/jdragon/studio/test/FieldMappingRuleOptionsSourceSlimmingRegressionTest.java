package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.FieldMappingRuleOptionView;
import com.jdragon.studio.infra.entity.FieldMappingRuleEntity;
import com.jdragon.studio.infra.mapper.FieldMappingRuleMapper;
import com.jdragon.studio.infra.mapper.FieldMappingRuleParamMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.service.FieldMappingRuleService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FieldMappingRuleOptionsSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(FieldMappingRuleEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FieldMappingRuleEntity.class);
        }
    }

    @Test
    void optionSummariesShouldSelectOnlyFieldsNeededByTransformerPicker() {
        FieldMappingRuleMapper fieldMappingRuleMapper = mock(FieldMappingRuleMapper.class);
        FieldMappingRuleService service = new FieldMappingRuleService(
                fieldMappingRuleMapper,
                mock(FieldMappingRuleParamMapper.class),
                mock(StudioUserMapper.class),
                mock(StudioSecurityService.class),
                new ObjectMapper());
        when(fieldMappingRuleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(rule()));

        List<FieldMappingRuleOptionView> options = service.optionSummaries("规整");

        assertThat(options).hasSize(1);
        FieldMappingRuleOptionView option = options.get(0);
        assertThat(option.getMappingName()).isEqualTo("客户手机号标准化");
        assertThat(option.getMappingCode()).isEqualTo("customer_phone_normalize");

        ArgumentCaptor<LambdaQueryWrapper<FieldMappingRuleEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fieldMappingRuleMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id", "deleted", "mapping_name", "mapping_type", "mapping_code", "enabled")
                .doesNotContain("description", "created_by", "created_at", "updated_at");
    }

    private FieldMappingRuleEntity rule() {
        FieldMappingRuleEntity entity = new FieldMappingRuleEntity();
        entity.setId(31L);
        entity.setMappingName("客户手机号标准化");
        entity.setMappingType("规整");
        entity.setMappingCode("customer_phone_normalize");
        entity.setEnabled(Integer.valueOf(1));
        entity.setDescription("用于客户域手机号清洗的详细参数说明");
        entity.setCreatedBy(1L);
        return entity;
    }
}
