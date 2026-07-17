package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.auth.AuthProfileView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.StudioExternalUserBindingEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.mapper.RoleMapper;
import com.jdragon.studio.infra.mapper.StudioExternalUserBindingMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.UserRoleMapper;
import com.jdragon.studio.infra.security.StudioUserPrincipal;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayAuthExchangeServiceTest {

    private static final String SHARED_SECRET = "test-gateway-secret";
    private static final String REQUEST_PATH = "/api/v1/auth/gateway/exchange";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private StudioUserMapper userMapper;
    private StudioExternalUserBindingMapper bindingMapper;
    private StudioUserEntity existingUser;
    private GatewayAuthExchangeService service;

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

    @BeforeEach
    void setUp() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getGateway().setTrustEnabled(true);
        properties.getGateway().setSharedSecret(SHARED_SECRET);

        userMapper = mock(StudioUserMapper.class);
        bindingMapper = mock(StudioExternalUserBindingMapper.class);
        StudioExternalUserBindingEntity binding = new StudioExternalUserBindingEntity();
        binding.setId(20L);
        binding.setStudioUserId(10L);
        binding.setProviderCode("GATEWAY");
        binding.setExternalUserId("gateway-user-1");
        when(bindingMapper.selectOne(any())).thenReturn(binding);

        existingUser = new StudioUserEntity();
        existingUser.setId(10L);
        existingUser.setTenantId("default");
        existingUser.setUsername("gateway_user");
        existingUser.setDisplayName("Gateway User");
        existingUser.setPasswordHash("encoded");
        existingUser.setMobilePhone("13800000009");
        existingUser.setEnabled(1);
        existingUser.setAuthSource("GATEWAY");
        when(userMapper.selectById(10L)).thenReturn(existingUser);

        StudioUserDetailsService userDetailsService = mock(StudioUserDetailsService.class);
        StudioUserPrincipal principal = new StudioUserPrincipal(
                10L, "default", "gateway_user", "encoded", true, Collections.emptyList());
        when(userDetailsService.loadUserByUsername("gateway_user")).thenReturn(principal);
        JwtTokenService jwtTokenService = mock(JwtTokenService.class);
        when(jwtTokenService.createToken("gateway_user")).thenReturn("studio-token");
        StudioAccessService accessService = mock(StudioAccessService.class);
        when(accessService.buildProfile(any(), isNull(), isNull(), any())).thenReturn(new AuthProfileView());

        service = new GatewayAuthExchangeService(
                properties,
                objectMapper,
                bindingMapper,
                userMapper,
                mock(UserRoleMapper.class),
                mock(RoleMapper.class),
                mock(PasswordEncoder.class),
                userDetailsService,
                jwtTokenService,
                accessService);
    }

    @Test
    void shouldSynchronizeValidMobilePhone() throws Exception {
        exchange(userInfo("+86 139-0000-0001", null, true, false));

        assertEquals("13900000001", existingUser.getMobilePhone());
        verify(userMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void shouldClearStoredMobileWhenGatewayExplicitlyReturnsNull() throws Exception {
        exchange(userInfo(null, null, true, false));

        assertNull(existingUser.getMobilePhone());
        verify(userMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void shouldFallBackToPhoneNumberWhenMobilePhoneIsInvalid() throws Exception {
        exchange(userInfo("invalid", "138-0000-0002", true, true));

        assertEquals("13800000002", existingUser.getMobilePhone());
        verify(userMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void shouldPreserveStoredMobileWhenGatewayOmitsBothPhoneFields() throws Exception {
        exchange(userInfo(null, null, false, false));

        assertEquals("13800000009", existingUser.getMobilePhone());
        verify(userMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    private Map<String, Object> userInfo(String mobilePhone,
                                         String phoneNumber,
                                         boolean includeMobilePhone,
                                         boolean includePhoneNumber) {
        Map<String, Object> userInfo = new LinkedHashMap<String, Object>();
        userInfo.put("userId", "gateway-user-1");
        userInfo.put("account", "gateway_user");
        userInfo.put("userName", "Gateway User");
        if (includeMobilePhone) {
            userInfo.put("mobilePhone", mobilePhone);
        }
        if (includePhoneNumber) {
            userInfo.put("phoneNumber", phoneNumber);
        }
        return userInfo;
    }

    private void exchange(Map<String, Object> userInfo) throws Exception {
        String json = objectMapper.writeValueAsString(userInfo);
        String rawUserInfo = URLEncoder.encode(json, StandardCharsets.UTF_8.name());
        String timestamp = String.valueOf(System.currentTimeMillis());
        String signature = sign(rawUserInfo, timestamp);
        service.exchange(rawUserInfo, timestamp, REQUEST_PATH, signature, null, null);
    }

    private String sign(String rawUserInfo, String timestamp) throws Exception {
        String payload = rawUserInfo + "\n" + timestamp + "\n" + REQUEST_PATH;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SHARED_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
