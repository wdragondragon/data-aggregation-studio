package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.ElinkUserOptionView;
import com.jdragon.studio.infra.entity.StudioExternalUserBindingEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.mapper.StudioExternalUserBindingMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.UserRoleMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserManagementServiceTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(StudioUserEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), StudioUserEntity.class);
        }
        if (TableInfoHelper.getTableInfo(StudioExternalUserBindingEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), StudioExternalUserBindingEntity.class);
        }
    }

    @Test
    void shouldBindElinkAccountAndReturnSanitizedBinding() {
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        when(userMapper.selectList(any())).thenReturn(Collections.emptyList());
        doAnswer(invocation -> {
            StudioUserEntity user = invocation.getArgument(0);
            user.setId(10L);
            return 1;
        }).when(userMapper).insert(any(StudioUserEntity.class));
        StudioExternalUserBindingMapper bindingMapper = mock(StudioExternalUserBindingMapper.class);
        AtomicReference<StudioExternalUserBindingEntity> inserted =
                new AtomicReference<StudioExternalUserBindingEntity>();
        when(bindingMapper.selectOne(any())).thenAnswer(invocation -> inserted.get());
        doAnswer(invocation -> {
            StudioExternalUserBindingEntity binding = invocation.getArgument(0);
            binding.setId(20L);
            inserted.set(binding);
            return 1;
        }).when(bindingMapper).insert(any(StudioExternalUserBindingEntity.class));
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("encoded");
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        UserManagementService service = new UserManagementService(userMapper, mock(UserRoleMapper.class),
                encoder, security, bindingMapper);
        ElinkManagerOptionService optionService = mock(ElinkManagerOptionService.class);
        ElinkUserOptionView managerUser = new ElinkUserOptionView();
        managerUser.setUserId("elink-alice");
        managerUser.setName("Canonical Alice");
        managerUser.setEnabled(true);
        when(optionService.requireUser("elink-alice")).thenReturn(managerUser);
        service.setElinkManagerOptionService(optionService);
        StudioUserEntity request = new StudioUserEntity();
        request.setUsername("alice");
        request.setPasswordHash("secret");
        request.setMobilePhone("+86 138-0000-0001");
        request.setElinkUserId("elink-alice");

        StudioUserEntity saved = service.save(request);

        assertEquals("elink-alice", saved.getElinkUserId());
        assertEquals("Canonical Alice", saved.getElinkUserName());
        assertEquals("13800000001", saved.getMobilePhone());
        assertNull(saved.getPasswordHash());
        assertEquals("ELINK", inserted.get().getProviderCode());
        assertEquals(10L, inserted.get().getStudioUserId());
        verify(optionService).requireUser("elink-alice");
    }

    @Test
    void shouldRejectDuplicateElinkBindingAndHardDeleteExplicitClear() {
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        StudioUserEntity target = new StudioUserEntity();
        target.setId(10L);
        target.setTenantId("default");
        target.setUsername("alice");
        target.setPasswordHash("encoded");
        target.setEnabled(1);
        target.setAuthSource("LOCAL");
        when(userMapper.selectById(10L)).thenReturn(target);
        when(userMapper.selectList(any())).thenReturn(Collections.emptyList());
        StudioExternalUserBindingMapper bindingMapper = mock(StudioExternalUserBindingMapper.class);
        StudioExternalUserBindingEntity duplicate = new StudioExternalUserBindingEntity();
        duplicate.setStudioUserId(11L);
        duplicate.setExternalUserId("shared-elink");
        when(bindingMapper.selectOne(any())).thenReturn(duplicate);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        UserManagementService service = new UserManagementService(userMapper, mock(UserRoleMapper.class),
                mock(PasswordEncoder.class), security, bindingMapper);
        StudioUserEntity duplicateRequest = new StudioUserEntity();
        duplicateRequest.setId(10L);
        duplicateRequest.setUsername("alice");
        duplicateRequest.setElinkUserId("shared-elink");

        assertThrows(StudioException.class, () -> service.save(duplicateRequest));

        StudioUserEntity clearRequest = new StudioUserEntity();
        clearRequest.setId(10L);
        clearRequest.setUsername("alice");
        clearRequest.setClearElinkUserBinding(true);
        when(bindingMapper.selectOne(any())).thenReturn(null);
        StudioUserEntity cleared = service.save(clearRequest);

        assertNull(cleared.getElinkUserId());
        verify(bindingMapper).hardDeleteByProviderAndStudioUserId("ELINK", 10L);
    }

    @Test
    void shouldPreserveExistingBindingWithoutCallingManagerWhenBindingFieldsAreOmitted() {
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        StudioUserEntity target = new StudioUserEntity();
        target.setId(10L);
        target.setTenantId("default");
        target.setUsername("alice");
        target.setPasswordHash("encoded");
        target.setEnabled(1);
        target.setAuthSource("LOCAL");
        when(userMapper.selectById(10L)).thenReturn(target);
        when(userMapper.selectList(any())).thenReturn(Collections.emptyList());
        StudioExternalUserBindingMapper bindingMapper = mock(StudioExternalUserBindingMapper.class);
        StudioExternalUserBindingEntity binding = new StudioExternalUserBindingEntity();
        binding.setStudioUserId(10L);
        binding.setProviderCode("ELINK");
        binding.setExternalUserId("elink-alice");
        binding.setExternalAccount("Alice");
        when(bindingMapper.selectOne(any())).thenReturn(binding);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        UserManagementService service = new UserManagementService(userMapper, mock(UserRoleMapper.class),
                mock(PasswordEncoder.class), security, bindingMapper);
        ElinkManagerOptionService optionService = mock(ElinkManagerOptionService.class);
        service.setElinkManagerOptionService(optionService);
        StudioUserEntity request = new StudioUserEntity();
        request.setId(10L);
        request.setUsername("alice");

        StudioUserEntity saved = service.save(request);

        assertEquals("elink-alice", saved.getElinkUserId());
        verify(optionService, never()).requireUser(anyString());
    }

    @Test
    void shouldClearMobilePhoneWhenExplicitBlankValueIsSaved() {
        StudioUserMapper userMapper = mock(StudioUserMapper.class);
        StudioUserEntity target = new StudioUserEntity();
        target.setId(10L);
        target.setTenantId("default");
        target.setUsername("alice");
        target.setPasswordHash("encoded");
        target.setMobilePhone("13800000001");
        target.setEnabled(1);
        target.setAuthSource("LOCAL");
        when(userMapper.selectById(10L)).thenReturn(target);
        when(userMapper.selectList(any())).thenReturn(Collections.emptyList());
        StudioExternalUserBindingMapper bindingMapper = mock(StudioExternalUserBindingMapper.class);
        when(bindingMapper.selectOne(any())).thenReturn(null);
        StudioSecurityService security = mock(StudioSecurityService.class);
        when(security.hasAnyRole(any(String[].class))).thenReturn(true);
        UserManagementService service = new UserManagementService(userMapper, mock(UserRoleMapper.class),
                mock(PasswordEncoder.class), security, bindingMapper);
        StudioUserEntity request = new StudioUserEntity();
        request.setId(10L);
        request.setUsername("alice");
        request.setMobilePhone("");

        StudioUserEntity saved = service.save(request);

        assertNull(saved.getMobilePhone());
        verify(userMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }
}
