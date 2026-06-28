package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.enums.LineageLevel;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.DataModelLineageRelationEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataModelLineageRelationMapper;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.service.DataModelLineageService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataModelLineageSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        initTable(DataModelEntity.class);
        initTable(DatasourceEntity.class);
        initTable(DataModelLineageRelationEntity.class);
    }

    @Test
    void lineageGraphShouldUseSlimModelAndVisibilityQueries() {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        DataModelLineageRelationMapper relationMapper = mock(DataModelLineageRelationMapper.class);
        DataModelLineageService service = lineageService(dataModelMapper, datasourceMapper, relationMapper);

        when(dataModelMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(model());
        when(dataModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(model()));
        when(datasourceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(datasource());
        when(datasourceMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(datasource()));
        when(relationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(relation()));

        service.getModelLineage(21L, LineageLevel.TABLE);

        ArgumentCaptor<LambdaQueryWrapper<DataModelEntity>> focusModelCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dataModelMapper).selectOne(focusModelCaptor.capture());
        assertThat(focusModelCaptor.getValue().getSqlSelect())
                .contains("id", "tenant_id", "project_id", "datasource_id", "name", "physical_locator")
                .doesNotContain("schema_version_id", "technical_metadata", "business_metadata");

        ArgumentCaptor<LambdaQueryWrapper<DataModelEntity>> visibleModelCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dataModelMapper).selectList(visibleModelCaptor.capture());
        assertThat(visibleModelCaptor.getValue().getSqlSelect())
                .isEqualTo("id");

        ArgumentCaptor<LambdaQueryWrapper<DatasourceEntity>> visibleDatasourceCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(datasourceMapper).selectList(visibleDatasourceCaptor.capture());
        assertThat(visibleDatasourceCaptor.getValue().getSqlSelect())
                .isEqualTo("id");

        ArgumentCaptor<LambdaQueryWrapper<DatasourceEntity>> focusDatasourceCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(datasourceMapper).selectOne(focusDatasourceCaptor.capture());
        assertThat(focusDatasourceCaptor.getValue().getSqlSelect())
                .contains("id", "name", "type_code", "technical_metadata")
                .doesNotContain("business_metadata", "connection_fingerprint");
    }

    private static void initTable(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
        }
    }

    private DataModelLineageService lineageService(DataModelMapper dataModelMapper,
                                                   DatasourceMapper datasourceMapper,
                                                   DataModelLineageRelationMapper relationMapper) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.requireCurrentProjectId()).thenReturn(100L);
        when(accessService.currentProjectId()).thenReturn(100L);
        when(accessService.sharedResourceIdList(any())).thenReturn(Collections.emptyList());
        when(accessService.sharedResourceIds(any())).thenReturn(Collections.emptySet());
        return new DataModelLineageService(
                relationMapper,
                mock(CollectionTaskDefinitionMapper.class),
                dataModelMapper,
                datasourceMapper,
                mock(RunRecordMapper.class),
                mock(StudioUserMapper.class),
                accessService,
                securityService,
                new ObjectMapper(),
                Runnable::run,
                mock(PlatformTransactionManager.class));
    }

    private DataModelEntity model() {
        DataModelEntity entity = new DataModelEntity();
        entity.setId(21L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setDatasourceId(11L);
        entity.setName("客户经营画像表");
        entity.setPhysicalLocator("lt_reg_customer_profile");
        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        technicalMetadata.put("columns", Collections.singletonList("customer_id"));
        entity.setTechnicalMetadata(technicalMetadata);
        entity.setBusinessMetadata(Collections.singletonMap("owner", "客户经营域"));
        return entity;
    }

    private DatasourceEntity datasource() {
        DatasourceEntity entity = new DatasourceEntity();
        entity.setId(11L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setName("长期回归-客户经营画像数据源");
        entity.setTypeCode("mysql8");
        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        technicalMetadata.put("host", "127.0.0.1");
        technicalMetadata.put("port", "3306");
        technicalMetadata.put("database", "studio_longterm_regression");
        entity.setTechnicalMetadata(technicalMetadata);
        entity.setBusinessMetadata(Collections.singletonMap("owner", "客户经营域"));
        return entity;
    }

    private DataModelLineageRelationEntity relation() {
        DataModelLineageRelationEntity entity = new DataModelLineageRelationEntity();
        entity.setId(31L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setLevel("TABLE");
        entity.setSourceType("MANUAL");
        entity.setSourceDatasourceId(11L);
        entity.setSourceDatasourceNameSnapshot("长期回归-客户经营画像数据源");
        entity.setSourceDatasourceTypeSnapshot("mysql8");
        entity.setSourceDatabaseNameSnapshot("studio_longterm_regression");
        entity.setSourceModelId(21L);
        entity.setSourceModelNameSnapshot("客户经营画像表");
        entity.setSourceModelLocatorSnapshot("lt_reg_customer_profile");
        entity.setTargetDatasourceId(11L);
        entity.setTargetDatasourceNameSnapshot("长期回归-客户经营画像数据源");
        entity.setTargetDatasourceTypeSnapshot("mysql8");
        entity.setTargetDatabaseNameSnapshot("studio_longterm_regression");
        entity.setTargetModelId(21L);
        entity.setTargetModelNameSnapshot("客户经营画像表");
        entity.setTargetModelLocatorSnapshot("lt_reg_customer_profile");
        entity.setLatestRunStatus("SUCCESS");
        return entity;
    }
}
