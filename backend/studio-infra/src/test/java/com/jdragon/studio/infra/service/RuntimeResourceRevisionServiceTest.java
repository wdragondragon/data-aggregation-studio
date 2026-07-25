package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.entity.DataDevelopmentScriptEntity;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.entity.DatasourceClusterBindingEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataDevelopmentScriptMapper;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.mapper.DatasourceClusterBindingMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeResourceRevisionServiceTest {

    @Test
    void scriptRevisionShouldChangeWhenDatasourceModelOrModelDatasourceChanges() {
        DataDevelopmentScriptMapper scriptMapper = mock(DataDevelopmentScriptMapper.class);
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        DatasourceClusterBindingMapper bindingMapper = mock(DatasourceClusterBindingMapper.class);
        DataModelMapper modelMapper = mock(DataModelMapper.class);
        RuntimeResourceRevisionService service = new RuntimeResourceRevisionService(
                mock(CollectionTaskDefinitionMapper.class),
                mock(QualityTaskDefinitionMapper.class),
                scriptMapper,
                datasourceMapper,
                bindingMapper,
                modelMapper);

        DataDevelopmentScriptEntity script = new DataDevelopmentScriptEntity();
        script.setId(101L);
        script.setDatasourceId(201L);
        script.setUpdatedAt(LocalDateTime.of(2026, 7, 20, 9, 0));
        Map<String, Object> executionConfig = new LinkedHashMap<String, Object>();
        executionConfig.put("selectedModelIds", List.of(301L));
        script.setExecutionConfigJson(executionConfig);

        DatasourceEntity directDatasource = datasource(201L, "direct-v1");
        DatasourceEntity modelDatasource = datasource(202L, "model-v1");
        DataModelEntity model = new DataModelEntity();
        model.setId(301L);
        model.setDatasourceId(202L);
        model.setPhysicalLocator("public.customer");
        model.setUpdatedAt(LocalDateTime.of(2026, 7, 20, 9, 0));

        when(scriptMapper.selectById(101L)).thenReturn(script);
        when(datasourceMapper.selectById(201L)).thenReturn(directDatasource);
        when(datasourceMapper.selectById(202L)).thenReturn(modelDatasource);
        when(modelMapper.selectById(301L)).thenReturn(model);
        DatasourceClusterBindingEntity binding = new DatasourceClusterBindingEntity();
        binding.setId(401L);
        binding.setTenantId("default");
        binding.setDatasourceId(201L);
        binding.setRuntimeClusterId(46L);
        binding.setEnabled(1);
        binding.setUpdatedAt(LocalDateTime.of(2026, 7, 20, 9, 0));
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));

        String queuedRevision = service.scriptRevision(101L);
        assertThat(queuedRevision).hasSize(64);

        directDatasource.setConnectionFingerprint("direct-v2");
        assertThat(service.scriptRevision(101L)).isNotEqualTo(queuedRevision);
        directDatasource.setConnectionFingerprint("direct-v1");

        model.setPhysicalLocator("public.customer_v2");
        assertThat(service.scriptRevision(101L)).isNotEqualTo(queuedRevision);
        model.setPhysicalLocator("public.customer");

        modelDatasource.setConnectionFingerprint("model-v2");
        assertThat(service.scriptRevision(101L)).isNotEqualTo(queuedRevision);
        modelDatasource.setConnectionFingerprint("model-v1");

        binding.setEnabled(0);
        assertThat(service.scriptRevision(101L)).isNotEqualTo(queuedRevision);

        assertThatThrownBy(() -> service.scriptRevision(101L,
                LocalDateTime.of(2026, 7, 20, 8, 59)))
                .hasMessageContaining("changed while dispatching");
    }

    private DatasourceEntity datasource(Long id, String fingerprint) {
        DatasourceEntity datasource = new DatasourceEntity();
        datasource.setId(id);
        datasource.setTenantId("default");
        datasource.setConnectionFingerprint(fingerprint);
        datasource.setEnabled(1);
        datasource.setExecutable(1);
        datasource.setUpdatedAt(LocalDateTime.of(2026, 7, 20, 9, 0));
        return datasource;
    }
}
