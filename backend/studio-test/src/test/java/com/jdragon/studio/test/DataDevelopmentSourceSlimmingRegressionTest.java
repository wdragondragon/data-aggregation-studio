package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataDevelopmentScriptListView;
import com.jdragon.studio.dto.model.DataDevelopmentScriptView;
import com.jdragon.studio.dto.model.DataDevelopmentTreeNode;
import com.jdragon.studio.dto.model.DataSourceListView;
import com.jdragon.studio.dto.model.ScriptEnvironmentOptionView;
import com.jdragon.studio.infra.entity.DataDevelopmentDirectoryEntity;
import com.jdragon.studio.infra.entity.DataDevelopmentScriptEntity;
import com.jdragon.studio.infra.mapper.DataDevelopmentDirectoryMapper;
import com.jdragon.studio.infra.mapper.DataDevelopmentScriptMapper;
import com.jdragon.studio.infra.service.DataDevelopmentService;
import com.jdragon.studio.infra.service.DataDevelopmentSqlExecutor;
import com.jdragon.studio.infra.service.DataDevelopmentWorkerExecutionService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.ScriptEnvironmentRuntimeService;
import com.jdragon.studio.infra.service.ScriptEnvironmentService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataDevelopmentSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(DataDevelopmentScriptEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DataDevelopmentScriptEntity.class);
        }
        if (TableInfoHelper.getTableInfo(DataDevelopmentDirectoryEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DataDevelopmentDirectoryEntity.class);
        }
    }

    @Test
    void treeShouldBatchResolveJavaEnvironmentNames() {
        DataDevelopmentDirectoryMapper directoryMapper = mock(DataDevelopmentDirectoryMapper.class);
        DataDevelopmentScriptMapper scriptMapper = mock(DataDevelopmentScriptMapper.class);
        ScriptEnvironmentService scriptEnvironmentService = mock(ScriptEnvironmentService.class);
        DataDevelopmentService service = service(directoryMapper, scriptMapper, scriptEnvironmentService);
        when(directoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        when(scriptMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(scripts());
        when(scriptEnvironmentService.enabledOptionMapByIds(any())).thenReturn(environmentOptions());

        List<DataDevelopmentTreeNode> tree = service.tree();

        assertThat(tree).extracting(DataDevelopmentTreeNode::getName)
                .contains("长期回归-客户经营画像.java", "长期回归-订单风险校验.java");
        assertThat(tree).filteredOn(node -> "长期回归-客户经营画像.java".equals(node.getName()))
                .singleElement()
                .extracting(DataDevelopmentTreeNode::getEnvironmentName)
                .isEqualTo("长期回归-Java客户画像环境");
        assertThat(tree).filteredOn(node -> "长期回归-订单风险校验.java".equals(node.getName()))
                .singleElement()
                .extracting(DataDevelopmentTreeNode::getEnvironmentName)
                .isEqualTo("长期回归-Java风控环境");

        ArgumentCaptor<Set<Long>> captor = ArgumentCaptor.forClass(Set.class);
        verify(scriptEnvironmentService).enabledOptionMapByIds(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(31L, 32L);
        verify(scriptEnvironmentService, never()).requireEnabledEnvironment(any());
    }

    @Test
    void listScriptsShouldBatchResolveJavaEnvironmentNames() {
        DataDevelopmentDirectoryMapper directoryMapper = mock(DataDevelopmentDirectoryMapper.class);
        DataDevelopmentScriptMapper scriptMapper = mock(DataDevelopmentScriptMapper.class);
        ScriptEnvironmentService scriptEnvironmentService = mock(ScriptEnvironmentService.class);
        DataDevelopmentService service = service(directoryMapper, scriptMapper, scriptEnvironmentService);
        when(scriptMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(scripts());
        when(scriptEnvironmentService.enabledOptionMapByIds(any())).thenReturn(environmentOptions());

        List<DataDevelopmentScriptListView> scripts = service.listScripts(ScriptType.JAVA);

        assertThat(scripts).extracting(DataDevelopmentScriptListView::getEnvironmentName)
                .contains("长期回归-Java客户画像环境", "长期回归-Java风控环境");

        ArgumentCaptor<Set<Long>> captor = ArgumentCaptor.forClass(Set.class);
        verify(scriptEnvironmentService).enabledOptionMapByIds(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(31L, 32L);
        verify(scriptEnvironmentService, never()).requireEnabledEnvironment(any());
    }

    @Test
    void scriptDetailShouldResolveDatasourceNameFromBasicSummary() {
        DataDevelopmentDirectoryMapper directoryMapper = mock(DataDevelopmentDirectoryMapper.class);
        DataDevelopmentScriptMapper scriptMapper = mock(DataDevelopmentScriptMapper.class);
        ScriptEnvironmentService scriptEnvironmentService = mock(ScriptEnvironmentService.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataDevelopmentService service = service(directoryMapper, scriptMapper, scriptEnvironmentService, dataSourceService);
        DataDevelopmentScriptEntity entity = script(15L, "长期回归-客户分层统计.sql", ScriptType.SQL, null);
        entity.setDatasourceId(41L);
        entity.setContent("select customer_id, customer_level from dwd_customer_profile");
        when(scriptMapper.selectById(15L)).thenReturn(entity);
        when(dataSourceService.listBasicSummaryMap(any())).thenReturn(datasourceSummaries());

        DataDevelopmentScriptView script = service.getScript(15L);

        assertThat(script.getDatasourceName()).isEqualTo("长期回归-客户经营画像数据源");
        assertThat(script.getDatasourceTypeCode()).isEqualTo("mysql8");
        assertThat(script.getContent()).contains("dwd_customer_profile");

        ArgumentCaptor<Set<Long>> captor = ArgumentCaptor.forClass(Set.class);
        verify(dataSourceService).listBasicSummaryMap(captor.capture());
        assertThat(captor.getValue()).containsExactly(41L);
        verify(dataSourceService, never()).get(any());
    }

    private DataDevelopmentService service(DataDevelopmentDirectoryMapper directoryMapper,
                                           DataDevelopmentScriptMapper scriptMapper,
                                           ScriptEnvironmentService scriptEnvironmentService) {
        return service(directoryMapper, scriptMapper, scriptEnvironmentService, null);
    }

    private DataDevelopmentService service(DataDevelopmentDirectoryMapper directoryMapper,
                                           DataDevelopmentScriptMapper scriptMapper,
                                           ScriptEnvironmentService scriptEnvironmentService,
                                           DataSourceService dataSourceService) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService projectResourceAccessService = mock(ProjectResourceAccessService.class);
        DataSourceService effectiveDataSourceService = dataSourceService == null ? mock(DataSourceService.class) : dataSourceService;
        when(securityService.currentTenantId()).thenReturn("default");
        when(projectResourceAccessService.requireCurrentProjectId()).thenReturn(2068077680446365698L);
        when(projectResourceAccessService.sharedResourceIdList(anyString())).thenReturn(Collections.emptyList());
        when(effectiveDataSourceService.listBasicSummaryMap(any())).thenReturn(Collections.emptyMap());
        return new DataDevelopmentService(
                directoryMapper,
                scriptMapper,
                effectiveDataSourceService,
                mock(DataDevelopmentSqlExecutor.class),
                securityService,
                projectResourceAccessService,
                scriptEnvironmentService,
                mock(ScriptEnvironmentRuntimeService.class),
                mock(DataDevelopmentWorkerExecutionService.class),
                Collections.emptyList());
    }

    private List<DataDevelopmentScriptEntity> scripts() {
        return Arrays.asList(
                script(11L, "长期回归-客户经营画像.java", ScriptType.JAVA, 31L),
                script(12L, "长期回归-订单风险校验.java", ScriptType.JAVA, 32L),
                script(13L, "长期回归-客户分层统计.sql", ScriptType.SQL, null),
                script(14L, "长期回归-客户经营画像副本.java", ScriptType.JAVA, 31L));
    }

    private DataDevelopmentScriptEntity script(Long id, String fileName, ScriptType scriptType, Long environmentId) {
        DataDevelopmentScriptEntity entity = new DataDevelopmentScriptEntity();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setProjectId(2068077680446365698L);
        entity.setFileName(fileName);
        entity.setScriptType(scriptType.name());
        entity.setEnvironmentId(environmentId);
        return entity;
    }

    private Map<Long, DataSourceListView> datasourceSummaries() {
        Map<Long, DataSourceListView> summaries = new LinkedHashMap<Long, DataSourceListView>();
        DataSourceListView datasource = new DataSourceListView();
        datasource.setId(41L);
        datasource.setName("长期回归-客户经营画像数据源");
        datasource.setTypeCode("mysql8");
        summaries.put(41L, datasource);
        return summaries;
    }

    private Map<Long, ScriptEnvironmentOptionView> environmentOptions() {
        Map<Long, ScriptEnvironmentOptionView> options = new LinkedHashMap<Long, ScriptEnvironmentOptionView>();
        options.put(31L, environmentOption(31L, "长期回归-Java客户画像环境"));
        options.put(32L, environmentOption(32L, "长期回归-Java风控环境"));
        return options;
    }

    private ScriptEnvironmentOptionView environmentOption(Long id, String name) {
        ScriptEnvironmentOptionView view = new ScriptEnvironmentOptionView();
        view.setId(id);
        view.setEnvironmentName(name);
        view.setEnvironmentCode("env-" + id);
        view.setEnabled(true);
        return view;
    }
}
