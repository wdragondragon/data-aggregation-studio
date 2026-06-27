package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.enums.QualityRuleDimension;
import com.jdragon.studio.dto.enums.QualityRuleGranularity;
import com.jdragon.studio.dto.enums.QualityRuleScopeType;
import com.jdragon.studio.dto.enums.QualityTaskStatus;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.QualityRuleListView;
import com.jdragon.studio.dto.model.QualityTaskListView;
import com.jdragon.studio.infra.entity.QualityRuleEntity;
import com.jdragon.studio.infra.entity.QualityTaskDefinitionEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.QualityRuleInputParamMapper;
import com.jdragon.studio.infra.mapper.QualityRuleMapper;
import com.jdragon.studio.infra.mapper.QualityRuleOutputParamMapper;
import com.jdragon.studio.infra.mapper.QualityTaskAlertMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.QualityTaskScheduleMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DataDevelopmentSqlExecutor;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.QualityRuleService;
import com.jdragon.studio.infra.service.QualitySqlTemplateService;
import com.jdragon.studio.infra.service.QualityTaskExecutionService;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityListSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        initTableInfo(QualityRuleEntity.class);
        initTableInfo(QualityTaskDefinitionEntity.class);
    }

    @Test
    void qualityRuleListShouldSelectOnlyTableColumns() {
        QualityRuleMapper ruleMapper = mock(QualityRuleMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        QualityRuleService service = new QualityRuleService(
                ruleMapper,
                mock(QualityRuleInputParamMapper.class),
                mock(QualityRuleOutputParamMapper.class),
                mock(StudioUserMapper.class),
                securityService,
                accessService,
                mock(QualitySqlTemplateService.class));
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        when(ruleMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<QualityRuleEntity> page = invocation.getArgument(0);
            page.setTotal(1L);
            page.setRecords(Collections.singletonList(ruleEntity()));
            return page;
        });

        PageView<QualityRuleListView> page = service.list(1, 20, "客户", null, null, null);

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getRuleName()).isEqualTo("客户手机号完整性规则");
        ArgumentCaptor<LambdaQueryWrapper<QualityRuleEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(ruleMapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("rule_name", "rule_code", "scope_type", "rule_dimension", "granularity", "enabled")
                .doesNotContain("description", "supported_datasource_types_json", "logic_sql");
    }

    @Test
    void qualityTaskListShouldSelectOnlyTableColumns() {
        QualityTaskDefinitionMapper taskMapper = mock(QualityTaskDefinitionMapper.class);
        QualityTaskService service = qualityTaskService(taskMapper);
        when(taskMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<QualityTaskDefinitionEntity> page = invocation.getArgument(0);
            page.setTotal(1L);
            page.setRecords(Collections.singletonList(taskEntity()));
            return page;
        });

        PageView<QualityTaskListView> page = service.list(1, 20, "客户", null, null, null);

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getTaskName()).isEqualTo("客户手机号完整性巡检任务");
        ArgumentCaptor<LambdaQueryWrapper<QualityTaskDefinitionEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(taskMapper).selectPage(any(Page.class), captor.capture());
        assertTaskListSelectIsSlim(captor.getValue().getSqlSelect());
    }

    @Test
    void qualityTaskOnlineOptionsShouldSelectOnlyCandidateColumns() {
        QualityTaskDefinitionMapper taskMapper = mock(QualityTaskDefinitionMapper.class);
        QualityTaskService service = qualityTaskService(taskMapper);
        when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(taskEntity()));

        List<QualityTaskListView> tasks = service.listOnline();

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getTaskName()).isEqualTo("客户手机号完整性巡检任务");
        ArgumentCaptor<LambdaQueryWrapper<QualityTaskDefinitionEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(taskMapper).selectList(captor.capture());
        assertTaskListSelectIsSlim(captor.getValue().getSqlSelect());
    }

    private static void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
        }
    }

    private QualityTaskService qualityTaskService(QualityTaskDefinitionMapper taskMapper) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        return new QualityTaskService(
                taskMapper,
                mock(QualityTaskScheduleMapper.class),
                mock(QualityTaskAlertMapper.class),
                mock(DispatchTaskMapper.class),
                mock(RunRecordMapper.class),
                mock(DataSourceService.class),
                mock(DataModelService.class),
                mock(QualityRuleService.class),
                mock(QualityTaskExecutionService.class),
                mock(DataDevelopmentSqlExecutor.class),
                securityService,
                accessService,
                new ObjectMapper());
    }

    private void assertTaskListSelectIsSlim(String sqlSelect) {
        assertThat(sqlSelect)
                .contains("task_name", "task_code", "status", "rule_name_snapshot", "datasource_name_snapshot", "model_physical_locator", "column_name")
                .doesNotContain("where_clause", "resolved_sql_preview", "parameter_bindings_json", "rule_snapshot_json");
    }

    private QualityRuleEntity ruleEntity() {
        QualityRuleEntity entity = new QualityRuleEntity();
        entity.setId(11L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setDeleted(0);
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 27, 11, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 6, 27, 11, 1, 0));
        entity.setRuleName("客户手机号完整性规则");
        entity.setRuleCode("customer_phone_not_null");
        entity.setScopeType(QualityRuleScopeType.PROJECT.name());
        entity.setRuleDimension(QualityRuleDimension.COMPLETENESS.name());
        entity.setGranularity(QualityRuleGranularity.COLUMN.name());
        entity.setEnabled(1);
        return entity;
    }

    private QualityTaskDefinitionEntity taskEntity() {
        QualityTaskDefinitionEntity entity = new QualityTaskDefinitionEntity();
        entity.setId(21L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setDeleted(0);
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 27, 11, 10, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 6, 27, 11, 11, 0));
        entity.setCreatedBy(1L);
        entity.setTaskName("客户手机号完整性巡检任务");
        entity.setTaskCode("customer_phone_quality_check");
        entity.setStatus(QualityTaskStatus.ONLINE.name());
        entity.setRuleId(11L);
        entity.setRuleNameSnapshot("客户手机号完整性规则");
        entity.setRuleDimension(QualityRuleDimension.COMPLETENESS.name());
        entity.setGranularity(QualityRuleGranularity.COLUMN.name());
        entity.setDatasourceId(31L);
        entity.setDatasourceNameSnapshot("客户主数据源");
        entity.setDatasourceTypeCode("mysql8");
        entity.setModelId(41L);
        entity.setModelNameSnapshot("客户主档模型");
        entity.setModelPhysicalLocator("customer_profile");
        entity.setColumnName("phone");
        return entity;
    }
}
