package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.model.DataModelSqlHintView;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.service.BusinessMetaModelMetadataService;
import com.jdragon.studio.infra.service.DataModelAccessScopeService;
import com.jdragon.studio.infra.service.DataModelIndexRebuildQueueService;
import com.jdragon.studio.infra.service.DataModelSearchIndexService;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceTypeCapabilityService;
import com.jdragon.studio.infra.service.MetadataSchemaService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataModelSqlHintSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(DataModelEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DataModelEntity.class);
        }
    }

    @Test
    void sqlHintsShouldSelectOnlyColumnsNeededByEditorCompletion() {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService service = dataModelService(dataModelMapper, dataSourceService);
        when(dataSourceService.get(11L)).thenReturn(datasource());
        when(dataModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(model()));

        List<DataModelSqlHintView> hints = service.listSqlHintsByDatasource(11L);

        assertThat(hints).hasSize(1);
        DataModelSqlHintView hint = hints.get(0);
        assertThat(hint.getName()).isEqualTo("客户经营画像表");
        assertThat(hint.getPhysicalLocator()).isEqualTo("lt_reg_customer_profile");
        assertThat(hint.getColumns()).containsExactly("customer_id", "customer_name");

        ArgumentCaptor<LambdaQueryWrapper<DataModelEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dataModelMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id", "datasource_id", "name", "physical_locator", "technical_metadata")
                .doesNotContain("business_metadata", "schema_version_id", "created_at", "updated_at");
    }

    private DataModelService dataModelService(DataModelMapper dataModelMapper,
                                              DataSourceService dataSourceService) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        when(accessService.sharedResourceIdList(any())).thenReturn(Collections.emptyList());
        DataModelAccessScopeService scopeService = new DataModelAccessScopeService(dataModelMapper, securityService, accessService);
        return new DataModelService(
                dataModelMapper,
                dataSourceService,
                mock(AggregationSourceCapabilityProvider.class),
                mock(MetadataSchemaService.class),
                mock(DataModelSearchIndexService.class),
                mock(DataModelIndexRebuildQueueService.class),
                mock(BusinessMetaModelMetadataService.class),
                securityService,
                accessService,
                scopeService,
                mock(DatasourceTypeCapabilityService.class));
    }

    private DataSourceDefinition datasource() {
        DataSourceDefinition definition = new DataSourceDefinition();
        definition.setId(11L);
        definition.setName("长期回归-客户经营画像数据源");
        definition.setTypeCode("mysql8");
        return definition;
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
        technicalMetadata.put("columns", Arrays.asList(
                column("customer_id"),
                column("customer_name"),
                column("customer_id")));
        technicalMetadata.put("jdbcUrl", "jdbc:mysql://internal-host:3306/source");
        entity.setTechnicalMetadata(technicalMetadata);
        Map<String, Object> businessMetadata = new LinkedHashMap<String, Object>();
        businessMetadata.put("owner", "客户经营域");
        entity.setBusinessMetadata(businessMetadata);
        return entity;
    }

    private Map<String, Object> column(String name) {
        Map<String, Object> column = new LinkedHashMap<String, Object>();
        column.put("name", name);
        return column;
    }
}
