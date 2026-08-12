package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.mapper.ProjectMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.RoleMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.TenantMapper;
import com.jdragon.studio.infra.mapper.TenantMemberMapper;
import com.jdragon.studio.infra.mapper.UserRoleMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StudioExecutionContextServiceTest {

    @Test
    void disabledUserCannotBeUsedForBackgroundExecution() {
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        StudioUserEntity disabled = new StudioUserEntity();
        disabled.setId(77L);
        disabled.setEnabled(0);
        when(userMapper.selectById(77L)).thenReturn(disabled);
        StudioAccessService accessService = new StudioAccessService(
                userMapper, mock(UserRoleMapper.class), mock(RoleMapper.class),
                mock(TenantMapper.class), mock(TenantMemberMapper.class),
                mock(ProjectMapper.class), mock(ProjectMemberMapper.class));

        assertThatThrownBy(() -> accessService.buildExecutionContext(77L, "tenant-a", 501L))
                .isInstanceOfSatisfying(StudioException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(StudioErrorCode.UNAUTHORIZED);
                    assertThat(exception.getMessage()).contains("disabled or missing");
                });
    }
}
