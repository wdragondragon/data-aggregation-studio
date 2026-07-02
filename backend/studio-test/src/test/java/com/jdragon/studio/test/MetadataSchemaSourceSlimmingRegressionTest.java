package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.enums.SchemaStatus;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.infra.entity.MetaSchemaEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.mapper.MetaFieldDefinitionMapper;
import com.jdragon.studio.infra.mapper.MetaSchemaMapper;
import com.jdragon.studio.infra.mapper.MetaSchemaVersionMapper;
import com.jdragon.studio.infra.service.DataModelScopedIndexRefreshService;
import com.jdragon.studio.infra.service.DatasourceTypeCapabilityService;
import com.jdragon.studio.infra.service.MetadataSchemaService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetadataSchemaSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(MetaSchemaEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MetaSchemaEntity.class);
        }
    }

    @Test
    void technicalMetaModelLookupShouldQueryTargetSchemaCodeInsteadOfLoadingAllSchemaFields() {
        MetaSchemaMapper schemaMapper = mock(MetaSchemaMapper.class);
        MetadataSchemaService service = metadataSchemaService(schemaMapper);
        when(schemaMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(schema());

        MetadataSchemaDefinition result = service.findTechnicalMetaModel("mysql8", "table");

        assertThat(result).isNotNull();
        assertThat(result.getSchemaCode()).isEqualTo("technical:mysql8:table");
        assertThat(result.getFields()).isEmpty();

        ArgumentCaptor<LambdaQueryWrapper<MetaSchemaEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(schemaMapper).selectOne(captor.capture());
        verify(schemaMapper, never()).selectList(any(LambdaQueryWrapper.class));
        assertThat(captor.getValue().getTargetSql().toLowerCase())
                .contains("schema_code")
                .doesNotContain("current_version_id in");
    }

    private MetadataSchemaService metadataSchemaService(MetaSchemaMapper schemaMapper) {
        return new MetadataSchemaService(
                schemaMapper,
                mock(MetaSchemaVersionMapper.class),
                mock(MetaFieldDefinitionMapper.class),
                mock(DatasourceMapper.class),
                mock(DataModelMapper.class),
                mock(DatasourceTypeCapabilityService.class),
                mock(DataModelScopedIndexRefreshService.class));
    }

    private MetaSchemaEntity schema() {
        MetaSchemaEntity entity = new MetaSchemaEntity();
        entity.setId(101L);
        entity.setSchemaCode("technical:mysql8:table");
        entity.setSchemaName("MYSQL8 表信息");
        entity.setObjectType("model");
        entity.setTypeCode("mysql8.table");
        entity.setStatus(SchemaStatus.PUBLISHED.name());
        entity.setDescription("META_MODEL_CONFIG:{\"domain\":\"TECHNICAL\",\"datasourceType\":\"mysql8\",\"metaModelCode\":\"table\"}");
        return entity;
    }
}
