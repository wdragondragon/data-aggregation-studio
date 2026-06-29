package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.model.EnvironmentDependencyOptionView;
import com.jdragon.studio.dto.model.ScriptEnvironmentOptionView;
import com.jdragon.studio.infra.entity.EnvironmentDependencyEntity;
import com.jdragon.studio.infra.entity.ScriptEnvironmentEntity;
import com.jdragon.studio.infra.mapper.EnvironmentDependencyFileMapper;
import com.jdragon.studio.infra.mapper.EnvironmentDependencyMapper;
import com.jdragon.studio.infra.mapper.ScriptEnvironmentDependencyRelMapper;
import com.jdragon.studio.infra.mapper.ScriptEnvironmentMapper;
import com.jdragon.studio.infra.service.CloudObjectStorageService;
import com.jdragon.studio.infra.service.EnvironmentDependencyService;
import com.jdragon.studio.infra.service.ScriptEnvironmentRuntimeService;
import com.jdragon.studio.infra.service.ScriptEnvironmentService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScriptEnvironmentOptionsSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(ScriptEnvironmentEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ScriptEnvironmentEntity.class);
        }
        if (TableInfoHelper.getTableInfo(EnvironmentDependencyEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), EnvironmentDependencyEntity.class);
        }
    }

    @Test
    void scriptEnvironmentOptionsShouldSelectOnlyDropdownFields() {
        ScriptEnvironmentMapper environmentMapper = mock(ScriptEnvironmentMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ScriptEnvironmentService service = new ScriptEnvironmentService(
                environmentMapper,
                mock(ScriptEnvironmentDependencyRelMapper.class),
                mock(EnvironmentDependencyMapper.class),
                mock(EnvironmentDependencyService.class),
                securityService,
                mock(ObjectProvider.class));
        when(securityService.currentTenantId()).thenReturn("default");
        when(environmentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(scriptEnvironment());
        when(environmentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(scriptEnvironment()));

        List<ScriptEnvironmentOptionView> options = service.options(true);

        assertThat(options).hasSize(1);
        assertThat(options.get(0).getEnvironmentName()).isEqualTo("长期回归-Java脚本运行环境");
        assertThat(options.get(0).getEnvironmentCode()).isEqualTo("longterm-java-env");

        ArgumentCaptor<LambdaQueryWrapper<ScriptEnvironmentEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(environmentMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id", "tenant_id", "deleted", "created_at", "updated_at", "environment_name", "environment_code", "enabled")
                .doesNotContain("description", "use_application_parent", "environment_version");
    }

    @Test
    void environmentDependencyOptionsShouldSelectOnlyDropdownFields() {
        EnvironmentDependencyMapper dependencyMapper = mock(EnvironmentDependencyMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        EnvironmentDependencyService service = new EnvironmentDependencyService(
                dependencyMapper,
                mock(EnvironmentDependencyFileMapper.class),
                mock(ScriptEnvironmentDependencyRelMapper.class),
                mock(ScriptEnvironmentMapper.class),
                securityService,
                mock(ObjectProvider.class),
                mock(CloudObjectStorageService.class));
        when(securityService.currentTenantId()).thenReturn("default");
        when(dependencyMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(dependency()));

        List<EnvironmentDependencyOptionView> options = service.options(true);

        assertThat(options).hasSize(1);
        assertThat(options.get(0).getName()).isEqualTo("长期回归-客户经营依赖包");
        assertThat(options.get(0).getVersion()).isEqualTo("1.0.0");

        ArgumentCaptor<LambdaQueryWrapper<EnvironmentDependencyEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dependencyMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id", "tenant_id", "deleted", "created_at", "updated_at", "name", "version", "script_type", "enabled")
                .doesNotContain("artifact_url", "artifact_type", "checksum", "description");
    }

    @Test
    void scriptEnvironmentBatchOptionsShouldSelectOnlyDropdownFields() {
        ScriptEnvironmentMapper environmentMapper = mock(ScriptEnvironmentMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ScriptEnvironmentService service = new ScriptEnvironmentService(
                environmentMapper,
                mock(ScriptEnvironmentDependencyRelMapper.class),
                mock(EnvironmentDependencyMapper.class),
                mock(EnvironmentDependencyService.class),
                securityService,
                mock(ObjectProvider.class));
        when(securityService.currentTenantId()).thenReturn("default");
        when(environmentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(scriptEnvironment()));
        Set<Long> environmentIds = new LinkedHashSet<Long>();
        environmentIds.add(31L);

        Map<Long, ScriptEnvironmentOptionView> options = service.enabledOptionMapByIds(environmentIds);

        assertThat(options).containsKey(31L);
        assertThat(options.get(31L).getEnvironmentName()).isEqualTo("长期回归-Java脚本运行环境");

        ArgumentCaptor<LambdaQueryWrapper<ScriptEnvironmentEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(environmentMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id", "tenant_id", "deleted", "created_at", "updated_at", "environment_name", "environment_code", "enabled")
                .doesNotContain("description", "use_application_parent", "environment_version");
    }

    private ScriptEnvironmentEntity scriptEnvironment() {
        ScriptEnvironmentEntity entity = new ScriptEnvironmentEntity();
        entity.setId(31L);
        entity.setTenantId("default");
        entity.setDeleted(Integer.valueOf(0));
        entity.setEnvironmentName("长期回归-Java脚本运行环境");
        entity.setEnvironmentCode("longterm-java-env");
        entity.setEnabled(Integer.valueOf(1));
        entity.setUseApplicationParent(Integer.valueOf(1));
        entity.setEnvironmentVersion(Long.valueOf(7L));
        entity.setDescription("下拉不需要读取的运行环境描述");
        return entity;
    }

    private EnvironmentDependencyEntity dependency() {
        EnvironmentDependencyEntity entity = new EnvironmentDependencyEntity();
        entity.setId(41L);
        entity.setTenantId("default");
        entity.setDeleted(Integer.valueOf(0));
        entity.setName("长期回归-客户经营依赖包");
        entity.setVersion("1.0.0");
        entity.setScriptType("JAVA");
        entity.setEnabled(Integer.valueOf(1));
        entity.setArtifactUrl("oss://studio/env/longterm/customer.jar");
        entity.setArtifactType("JAR");
        entity.setChecksum("sha256-hidden");
        entity.setDescription("下拉不需要读取的依赖包描述");
        return entity;
    }
}
