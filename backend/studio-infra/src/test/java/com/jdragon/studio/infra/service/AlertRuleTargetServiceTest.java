package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.entity.AlertChannelEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.mapper.AlertChannelMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.ProjectWorkerBindingMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlertRuleTargetServiceTest {

    @BeforeAll
    static void initTableInfo() {
        for (Class<?> type : new Class<?>[]{AlertChannelEntity.class, ProjectMemberEntity.class,
                StudioUserEntity.class}) {
            if (TableInfoHelper.getTableInfo(type) == null) {
                TableInfoHelper.initTableInfo(
                        new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
            }
        }
    }

    @Test
    void shouldRequireEffectiveSourceForRuleRecipientElinkChannel() {
        AlertChannelMapper channelMapper = mock(AlertChannelMapper.class);
        AlertChannelEntity channel = new AlertChannelEntity();
        channel.setId(50L);
        channel.setTenantId("tenant-a");
        channel.setProjectId(20L);
        channel.setChannelType("ELINK");
        channel.setConfigJson(new LinkedHashMap<String, Object>(java.util.Map.of(
                "recipientMode", "RULE_RECIPIENTS", "targetType", "PERSONAL")));
        when(channelMapper.selectList(any())).thenReturn(Collections.singletonList(channel));
        ProjectMemberMapper memberMapper = mock(ProjectMemberMapper.class);
        when(memberMapper.selectList(any())).thenReturn(Collections.emptyList());
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        AlertRuleTargetService service = service(channelMapper, memberMapper, userMapper);

        assertThrows(StudioException.class, () -> service.validateRuleRecipientChannels(
                Collections.singletonList(50L), "COLLECTION_TASK", 30L,
                Collections.emptyList(), false, false, "tenant-a", 20L));

        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setTenantId("tenant-a");
        member.setProjectId(20L);
        member.setUserId(9L);
        member.setStatus("ACTIVE");
        StudioUserEntity user = new StudioUserEntity();
        user.setId(9L);
        user.setTenantId("default");
        user.setEnabled(1);
        when(memberMapper.selectList(any())).thenReturn(Collections.singletonList(member));
        when(userMapper.selectByIds(any())).thenReturn(Collections.singletonList(user));

        assertDoesNotThrow(() -> service.validateRuleRecipientChannels(
                Collections.singletonList(50L), "COLLECTION_TASK", 30L,
                Collections.singletonList(9L), false, false, "tenant-a", 20L));
    }

    private AlertRuleTargetService service(AlertChannelMapper channelMapper,
                                           ProjectMemberMapper memberMapper,
                                           StudioUserMapper userMapper) {
        return new AlertRuleTargetService(channelMapper, mock(AlertRuleDefinitionRegistry.class),
                mock(StudioSecurityService.class), mock(ProjectResourceAccessService.class),
                mock(CollectionTaskDefinitionMapper.class), mock(QualityTaskDefinitionMapper.class),
                mock(WorkflowDefinitionMapper.class), mock(DataServiceDefinitionMapper.class),
                mock(DataIngestionServiceMapper.class), mock(ProtocolConversionServiceMapper.class),
                mock(ProjectWorkerBindingMapper.class), memberMapper, userMapper);
    }
}
