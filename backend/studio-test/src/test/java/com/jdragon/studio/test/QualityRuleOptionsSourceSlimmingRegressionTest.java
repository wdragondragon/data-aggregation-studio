package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.enums.QualityRuleDimension;
import com.jdragon.studio.dto.enums.QualityRuleGranularity;
import com.jdragon.studio.dto.enums.QualityRuleScopeType;
import com.jdragon.studio.dto.model.QualityRuleOptionView;
import com.jdragon.studio.infra.entity.QualityRuleEntity;
import com.jdragon.studio.infra.mapper.QualityRuleInputParamMapper;
import com.jdragon.studio.infra.mapper.QualityRuleMapper;
import com.jdragon.studio.infra.mapper.QualityRuleOutputParamMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.QualityRuleService;
import com.jdragon.studio.infra.service.QualitySqlTemplateService;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QualityRuleOptionsSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(QualityRuleEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), QualityRuleEntity.class);
        }
    }

    @Test
    void optionSummariesShouldSelectOnlyFieldsNeededByQualityTaskRulePicker() {
        QualityRuleMapper ruleMapper = mock(QualityRuleMapper.class);
        QualityRuleInputParamMapper inputParamMapper = mock(QualityRuleInputParamMapper.class);
        QualityRuleOutputParamMapper outputParamMapper = mock(QualityRuleOutputParamMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        QualityRuleService service = new QualityRuleService(
                ruleMapper,
                inputParamMapper,
                outputParamMapper,
                mock(StudioUserMapper.class),
                securityService,
                accessService,
                mock(QualitySqlTemplateService.class));
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        when(ruleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(ruleEntity()));

        List<QualityRuleOptionView> options = service.optionSummaries("完整性", "COLUMN", "mysql8", true);

        assertThat(options).hasSize(1);
        QualityRuleOptionView option = options.get(0);
        assertThat(option.getRuleName()).isEqualTo("客户手机号完整性规则");
        assertThat(option.getSupportedDatasourceTypes()).containsExactly("mysql8");

        ArgumentCaptor<LambdaQueryWrapper<QualityRuleEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(ruleMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id", "tenant_id", "project_id", "deleted", "rule_name", "rule_code",
                        "scope_type", "rule_dimension", "supported_datasource_types_json", "granularity", "enabled")
                .doesNotContain("description", "logic_sql", "created_by", "created_at", "updated_at");
        verifyNoInteractions(inputParamMapper, outputParamMapper);
    }

    private QualityRuleEntity ruleEntity() {
        QualityRuleEntity entity = new QualityRuleEntity();
        entity.setId(11L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setDeleted(0);
        entity.setRuleName("客户手机号完整性规则");
        entity.setRuleCode("customer_phone_not_null");
        entity.setScopeType(QualityRuleScopeType.PROJECT.name());
        entity.setRuleDimension(QualityRuleDimension.COMPLETENESS.name());
        entity.setSupportedDatasourceTypesJson(Collections.singletonList("mysql8"));
        entity.setGranularity(QualityRuleGranularity.COLUMN.name());
        entity.setEnabled(1);
        entity.setDescription("校验客户手机号字段是否完整的业务说明");
        entity.setLogicSql("select count(1) as total_count from ${table} where ${column} is null");
        entity.setCreatedBy(1L);
        return entity;
    }
}
