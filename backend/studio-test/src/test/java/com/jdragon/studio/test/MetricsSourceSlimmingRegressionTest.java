package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.enums.CollectionTaskType;
import com.jdragon.studio.dto.enums.QualityRuleDimension;
import com.jdragon.studio.dto.enums.QualityRuleGranularity;
import com.jdragon.studio.dto.enums.QualityTaskStatus;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.CollectionTaskSourceBinding;
import com.jdragon.studio.dto.model.CollectionTaskTargetBinding;
import com.jdragon.studio.dto.model.DataModelListView;
import com.jdragon.studio.dto.model.DataSourceListView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.QualityAssetRiskView;
import com.jdragon.studio.dto.model.QualityIssueAssigneeOptionView;
import com.jdragon.studio.dto.model.QualityTaskListView;
import com.jdragon.studio.dto.model.request.QualityAssetQueryRequest;
import com.jdragon.studio.dto.model.request.QualityIssueQueryRequest;
import com.jdragon.studio.dto.model.request.QualityMetricDashboardQueryRequest;
import com.jdragon.studio.dto.model.request.RunMetricDashboardQueryRequest;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.QualityIssueEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.QualityIssueCommentMapper;
import com.jdragon.studio.infra.mapper.QualityIssueEventMapper;
import com.jdragon.studio.infra.mapper.QualityIssueMapper;
import com.jdragon.studio.infra.mapper.QualityMetricSnapshotMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.RunMetricSqlProvider;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.QualityIssueService;
import com.jdragon.studio.infra.service.QualityMetricsService;
import com.jdragon.studio.infra.service.QualityTaskService;
import com.jdragon.studio.infra.service.RunMetricSummaryMapper;
import com.jdragon.studio.infra.service.RunMetricsService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricsSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(RunRecordEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), RunRecordEntity.class);
        }
        if (TableInfoHelper.getTableInfo(QualityIssueEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), QualityIssueEntity.class);
        }
        if (TableInfoHelper.getTableInfo(ProjectMemberEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProjectMemberEntity.class);
        }
        if (TableInfoHelper.getTableInfo(StudioUserEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), StudioUserEntity.class);
        }
    }

    @Test
    void runMetricsDashboardShouldUseSourceAggregatesInsteadOfFullRunRecordRows() {
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        RunMetricsService service = new RunMetricsService(collectionTaskService, runRecordMapper, securityService, new RunMetricSummaryMapper());
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentProjectId()).thenReturn(100L);
        when(collectionTaskService.listMetricBindings()).thenReturn(Collections.singletonList(collectionTask()));
        when(runRecordMapper.selectRunMetricDashboardBuckets(any(), any(), any(), any(), any())).thenReturn(Collections.emptyList());
        when(runRecordMapper.selectRunMetricDashboardTaskAggregates(any(), any(), any(), any(), any())).thenReturn(Collections.emptyList());

        service.query(runMetricRequest());

        verify(runRecordMapper).selectRunMetricDashboardBuckets(any(), any(), any(), any(), any());
        verify(runRecordMapper).selectRunMetricDashboardTaskAggregates(any(), any(), any(), any(), any());
        verify(runRecordMapper, never()).selectList(any(LambdaQueryWrapper.class));

        RunMetricSqlProvider provider = new RunMetricSqlProvider();
        String bucketSql = provider.selectDashboardBuckets();
        String taskSql = provider.selectDashboardTaskAggregates();
        assertTrue(bucketSql.contains("group by substr(ended_at, 1, 10)"));
        assertTrue(taskSql.contains("group by collection_task_id"));
        assertFalse(bucketSql.contains("payload_json"));
        assertFalse(bucketSql.contains("result_json"));
        assertFalse(bucketSql.contains("log_file_path"));
        assertFalse(taskSql.contains("payload_json"));
        assertFalse(taskSql.contains("result_json"));
        assertFalse(taskSql.contains("log_file_path"));
    }

    @Test
    void qualityMetricsDashboardShouldUseSummaryOptionsAndNarrowRunRecordSelect() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        QualityTaskService qualityTaskService = mock(QualityTaskService.class);
        QualityIssueService qualityIssueService = mock(QualityIssueService.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        QualityMetricSnapshotMapper snapshotMapper = mock(QualityMetricSnapshotMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        QualityMetricsService service = new QualityMetricsService(dataSourceService, dataModelService, qualityTaskService,
                qualityIssueService, runRecordMapper, snapshotMapper, securityService, accessService);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        when(dataSourceService.listBasicSummaries()).thenReturn(Collections.singletonList(datasource()));
        when(dataModelService.listSummaryPage(null, 1, 5000, null, null))
                .thenReturn(PageView.of(1, 5000, 1L, Collections.singletonList(model())));
        when(qualityTaskService.list(null, null, null, null)).thenReturn(Collections.singletonList(qualityTask()));
        when(runRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        when(qualityIssueService.listProjectIssues()).thenReturn(Collections.emptyList());
        when(qualityIssueService.listProjectIssueEvents(any(), any())).thenReturn(Collections.emptyList());

        service.queryDashboard(new QualityMetricDashboardQueryRequest());

        verify(dataSourceService).listBasicSummaries();
        verify(dataSourceService, never()).list();
        verify(dataModelService).listSummaryPage(null, 1, 5000, null, null);
        verify(dataModelService, never()).list();
        ArgumentCaptor<LambdaQueryWrapper<RunRecordEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(runRecordMapper).selectList(captor.capture());
        String sqlSelect = captor.getValue().getSqlSelect();
        assertTrue(sqlSelect.contains("quality_task_id"));
        assertTrue(sqlSelect.contains("result_json"));
        assertFalse(sqlSelect.contains("payload_json"));
        assertFalse(sqlSelect.contains("log_file_path"));
        assertFalse(sqlSelect.contains("log_object_key"));
    }

    @Test
    void qualityAssetsPageShouldReturnRequestedPageAndReuseSummarySources() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        QualityTaskService qualityTaskService = mock(QualityTaskService.class);
        QualityIssueService qualityIssueService = mock(QualityIssueService.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        QualityMetricSnapshotMapper snapshotMapper = mock(QualityMetricSnapshotMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        QualityMetricsService service = new QualityMetricsService(dataSourceService, dataModelService, qualityTaskService,
                qualityIssueService, runRecordMapper, snapshotMapper, securityService, accessService);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        when(dataSourceService.listBasicSummaries()).thenReturn(Collections.singletonList(datasource()));
        when(dataModelService.listSummaryPage(null, 1, 5000, null, null))
                .thenReturn(PageView.of(1, 5000, 2L, Arrays.asList(model(), secondModel())));
        when(qualityTaskService.list(null, null, null, null)).thenReturn(Arrays.asList(qualityTask(), secondQualityTask()));
        when(runRecordMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        when(qualityIssueService.listProjectIssues()).thenReturn(Collections.emptyList());
        when(qualityIssueService.listProjectIssueEvents(any(), any())).thenReturn(Collections.emptyList());
        QualityAssetQueryRequest request = new QualityAssetQueryRequest();
        request.setPageNo(2);
        request.setPageSize(1);

        PageView<QualityAssetRiskView> page = service.queryAssetsPage(request);

        assertEquals(2L, page.getTotal());
        assertEquals(2, page.getPageNo());
        assertEquals(1, page.getPageSize());
        assertEquals(1, page.getItems().size());
        assertEquals(Long.valueOf(3L), page.getItems().get(0).getModelId());
        verify(dataSourceService).listBasicSummaries();
        verify(dataSourceService, never()).list();
        verify(dataModelService).listSummaryPage(null, 1, 5000, null, null);
        verify(dataModelService, never()).list();
        ArgumentCaptor<LambdaQueryWrapper<RunRecordEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(runRecordMapper).selectList(captor.capture());
        String sqlSelect = captor.getValue().getSqlSelect();
        assertTrue(sqlSelect.contains("quality_task_id"));
        assertTrue(sqlSelect.contains("result_json"));
        assertFalse(sqlSelect.contains("payload_json"));
        assertFalse(sqlSelect.contains("log_file_path"));
    }

    @Test
    void qualityIssuePageShouldUseSourcePaginationAndNotSelectDetailEvidenceJson() {
        QualityIssueMapper issueMapper = mock(QualityIssueMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        QualityIssueService service = new QualityIssueService(issueMapper,
                mock(QualityIssueCommentMapper.class),
                mock(QualityIssueEventMapper.class),
                mock(QualityTaskService.class),
                mock(ProjectMemberMapper.class),
                mock(StudioUserMapper.class),
                securityService,
                accessService);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        Page<QualityIssueEntity> emptyPage = new Page<QualityIssueEntity>(1, 10);
        emptyPage.setRecords(Collections.emptyList());
        emptyPage.setTotal(0L);
        when(issueMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(emptyPage);

        service.queryPage(new QualityIssueQueryRequest(), 1, 10);

        ArgumentCaptor<LambdaQueryWrapper<QualityIssueEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(issueMapper).selectPage(any(Page.class), captor.capture());
        verify(issueMapper, never()).selectList(any(LambdaQueryWrapper.class));
        String sqlSelect = captor.getValue().getSqlSelect();
        assertTrue(sqlSelect.contains("issue_code"));
        assertTrue(sqlSelect.contains("latest_message"));
        assertFalse(sqlSelect.contains("current_evidence_json"));
        assertTrue(captor.getValue().getSqlSegment().contains("order by"));
    }

    @Test
    void qualityIssueAssigneeOptionsShouldSelectOnlyProjectMemberUserAndUserLabels() {
        QualityIssueMapper issueMapper = mock(QualityIssueMapper.class);
        ProjectMemberMapper projectMemberMapper = mock(ProjectMemberMapper.class);
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        QualityIssueService service = new QualityIssueService(issueMapper,
                mock(QualityIssueCommentMapper.class),
                mock(QualityIssueEventMapper.class),
                mock(QualityTaskService.class),
                projectMemberMapper,
                userMapper,
                securityService,
                accessService);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        when(projectMemberMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(projectMember()));
        when(userMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(user()));

        List<QualityIssueAssigneeOptionView> options = service.listAssigneeOptions();

        assertFalse(options.isEmpty());
        assertTrue("客户质量负责人".equals(options.get(0).getLabel()));
        ArgumentCaptor<LambdaQueryWrapper<ProjectMemberEntity>> memberCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(projectMemberMapper).selectList(memberCaptor.capture());
        String memberSqlSelect = memberCaptor.getValue().getSqlSelect();
        assertTrue(memberSqlSelect.contains("user_id"));
        assertFalse(memberSqlSelect.contains("role_code"));
        assertFalse(memberSqlSelect.contains("status"));
        assertFalse(memberSqlSelect.contains("created_at"));
        assertFalse(memberSqlSelect.contains("updated_at"));

        ArgumentCaptor<LambdaQueryWrapper<StudioUserEntity>> userCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userMapper).selectList(userCaptor.capture());
        String userSqlSelect = userCaptor.getValue().getSqlSelect();
        assertTrue(userSqlSelect.contains("id"));
        assertTrue(userSqlSelect.contains("username"));
        assertTrue(userSqlSelect.contains("display_name"));
        assertFalse(userSqlSelect.contains("password_hash"));
        assertFalse(userSqlSelect.contains("enabled"));
        assertFalse(userSqlSelect.contains("created_at"));
        assertFalse(userSqlSelect.contains("updated_at"));
        verify(issueMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    private RunMetricDashboardQueryRequest runMetricRequest() {
        RunMetricDashboardQueryRequest request = new RunMetricDashboardQueryRequest();
        request.setStartTime(LocalDateTime.now().minusDays(1L).toString());
        request.setEndTime(LocalDateTime.now().toString());
        return request;
    }

    private CollectionTaskDefinitionView collectionTask() {
        CollectionTaskDefinitionView view = new CollectionTaskDefinitionView();
        view.setId(10L);
        view.setName("客户订单采集");
        view.setTaskType(CollectionTaskType.SINGLE_TABLE);
        view.setStatus(CollectionTaskStatus.ONLINE);
        CollectionTaskSourceBinding source = new CollectionTaskSourceBinding();
        source.setDatasourceId(1L);
        source.setDatasourceName("客户库");
        source.setDatasourceTypeCode("mysql8");
        source.setModelId(2L);
        source.setModelName("客户订单明细");
        source.setModelPhysicalLocator("customer_order_detail");
        view.setSourceBindings(Collections.singletonList(source));
        CollectionTaskTargetBinding target = new CollectionTaskTargetBinding();
        target.setDatasourceId(3L);
        target.setDatasourceName("数仓库");
        target.setDatasourceTypeCode("mysql8");
        target.setModelId(4L);
        target.setModelName("客户订单汇总");
        target.setModelPhysicalLocator("customer_order_summary");
        view.setTargetBinding(target);
        return view;
    }

    private DataSourceListView datasource() {
        DataSourceListView view = new DataSourceListView();
        view.setId(1L);
        view.setName("质量测试库");
        view.setTypeCode("mysql8");
        return view;
    }

    private DataModelListView model() {
        DataModelListView view = new DataModelListView();
        view.setId(2L);
        view.setDatasourceId(1L);
        view.setName("客户质量明细");
        view.setPhysicalLocator("customer_quality_detail");
        return view;
    }

    private DataModelListView secondModel() {
        DataModelListView view = new DataModelListView();
        view.setId(3L);
        view.setDatasourceId(1L);
        view.setName("客户质量汇总");
        view.setPhysicalLocator("customer_quality_summary");
        return view;
    }

    private QualityTaskListView qualityTask() {
        QualityTaskListView view = new QualityTaskListView();
        view.setId(20L);
        view.setTaskName("客户手机号完整性检查");
        view.setStatus(QualityTaskStatus.ONLINE);
        view.setDatasourceId(1L);
        view.setDatasourceName("质量测试库");
        view.setDatasourceTypeCode("mysql8");
        view.setModelId(2L);
        view.setModelName("客户质量明细");
        view.setModelPhysicalLocator("customer_quality_detail");
        view.setRuleId(30L);
        view.setRuleName("手机号非空");
        view.setRuleDimension(QualityRuleDimension.COMPLETENESS);
        view.setGranularity(QualityRuleGranularity.COLUMN);
        return view;
    }

    private QualityTaskListView secondQualityTask() {
        QualityTaskListView view = qualityTask();
        view.setId(21L);
        view.setTaskName("客户画像唯一性检查");
        view.setModelId(3L);
        view.setModelName("客户质量汇总");
        view.setModelPhysicalLocator("customer_quality_summary");
        view.setRuleDimension(QualityRuleDimension.UNIQUENESS);
        view.setGranularity(QualityRuleGranularity.TABLE);
        return view;
    }

    private ProjectMemberEntity projectMember() {
        ProjectMemberEntity entity = new ProjectMemberEntity();
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setUserId(200L);
        entity.setRoleCode("PROJECT_MEMBER");
        entity.setStatus("ACTIVE");
        return entity;
    }

    private StudioUserEntity user() {
        StudioUserEntity entity = new StudioUserEntity();
        entity.setId(200L);
        entity.setTenantId("default");
        entity.setUsername("quality_owner");
        entity.setDisplayName("客户质量负责人");
        entity.setEnabled(1);
        entity.setPasswordHash("$2a$secret");
        return entity;
    }
}
