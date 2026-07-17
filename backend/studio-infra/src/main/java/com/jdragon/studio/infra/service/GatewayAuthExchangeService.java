package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.auth.AuthProfileView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RoleEntity;
import com.jdragon.studio.infra.entity.StudioExternalUserBindingEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.UserRoleEntity;
import com.jdragon.studio.infra.mapper.RoleMapper;
import com.jdragon.studio.infra.mapper.StudioExternalUserBindingMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.UserRoleMapper;
import com.jdragon.studio.infra.security.StudioUserPrincipal;
import lombok.Data;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

@Service
public class GatewayAuthExchangeService {

    private static final String SUPER_ADMIN_ACCOUNT = "SuperAdmin";

    private final StudioPlatformProperties platformProperties;
    private final ObjectMapper objectMapper;
    private final StudioExternalUserBindingMapper externalUserBindingMapper;
    private final StudioUserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final StudioUserDetailsService studioUserDetailsService;
    private final JwtTokenService jwtTokenService;
    private final StudioAccessService studioAccessService;

    public GatewayAuthExchangeService(StudioPlatformProperties platformProperties,
                                      ObjectMapper objectMapper,
                                      StudioExternalUserBindingMapper externalUserBindingMapper,
                                      StudioUserMapper userMapper,
                                      UserRoleMapper userRoleMapper,
                                      RoleMapper roleMapper,
                                      PasswordEncoder passwordEncoder,
                                      StudioUserDetailsService studioUserDetailsService,
                                      JwtTokenService jwtTokenService,
                                      StudioAccessService studioAccessService) {
        this.platformProperties = platformProperties;
        this.objectMapper = objectMapper;
        this.externalUserBindingMapper = externalUserBindingMapper;
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
        this.studioUserDetailsService = studioUserDetailsService;
        this.jwtTokenService = jwtTokenService;
        this.studioAccessService = studioAccessService;
    }

    @Transactional
    public AuthProfileView exchange(String rawUserInfo,
                                    String timestamp,
                                    String requestPath,
                                    String signature,
                                    String requestedTenantId,
                                    String requestedProjectId) {
        StudioPlatformProperties.GatewayProperties gatewayProperties = platformProperties.getGateway();
        if (gatewayProperties == null || !gatewayProperties.isTrustEnabled()) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Gateway trusted exchange is disabled");
        }

        String sharedSecret = requireValue(gatewayProperties.getSharedSecret(), "Studio gateway shared secret is not configured");
        rawUserInfo = requireValue(rawUserInfo, "Missing gateway user info");
        timestamp = requireValue(timestamp, "Missing gateway timestamp");
        requestPath = requireValue(requestPath, "Missing gateway request path");
        signature = requireValue(signature, "Missing gateway signature");

        verifyTimestamp(timestamp, gatewayProperties.getSignatureExpireSeconds());
        verifySignature(sharedSecret, rawUserInfo, timestamp, requestPath, signature);

        GatewayUserInfo userInfo = parseUserInfo(rawUserInfo);
        StudioUserEntity user = provisionUser(userInfo);
        UserDetails userDetails = studioUserDetailsService.loadUserByUsername(user.getUsername());
        if (!(userDetails instanceof StudioUserPrincipal)) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Invalid studio principal");
        }
        StudioUserPrincipal principal = (StudioUserPrincipal) userDetails;
        String token = jwtTokenService.createToken(principal.getUsername());
        return studioAccessService.buildProfile(
                principal,
                requestedTenantId,
                requestedProjectId,
                token);
    }

    private void verifyTimestamp(String timestampHeader, Long expireSeconds) {
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException ex) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Invalid gateway timestamp");
        }
        long maxSkew = (expireSeconds == null ? 300L : expireSeconds.longValue()) * 1000L;
        if (Math.abs(System.currentTimeMillis() - timestamp) > maxSkew) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Gateway signature expired");
        }
    }

    private void verifySignature(String sharedSecret,
                                 String rawUserInfo,
                                 String timestamp,
                                 String requestPath,
                                 String signature) {
        String expected = sign(sharedSecret, rawUserInfo, timestamp, requestPath);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Invalid gateway signature");
        }
    }

    private GatewayUserInfo parseUserInfo(String rawUserInfo) {
        try {
            String decoded = URLDecoder.decode(rawUserInfo, StandardCharsets.UTF_8.name());
            GatewayUserInfo userInfo = objectMapper.readValue(decoded, GatewayUserInfo.class);
            if (!hasText(userInfo.getUserId())) {
                throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Gateway user id is missing");
            }
            return userInfo;
        } catch (StudioException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Failed to parse gateway user info");
        }
    }

    private StudioUserEntity provisionUser(GatewayUserInfo userInfo) {
        StudioExternalUserBindingEntity binding = externalUserBindingMapper.selectOne(
                new LambdaQueryWrapper<StudioExternalUserBindingEntity>()
                        .eq(StudioExternalUserBindingEntity::getProviderCode, StudioConstants.GATEWAY_PROVIDER_CODE)
                        .eq(StudioExternalUserBindingEntity::getExternalUserId, userInfo.getUserId())
                        .last("limit 1"));

        StudioUserEntity user = binding == null ? null : userMapper.selectById(binding.getStudioUserId());
        boolean superAdminGatewayUser = isSuperAdminGatewayUser(userInfo);
        if (user == null && superAdminGatewayUser) {
            user = selectUserByUsername(SUPER_ADMIN_ACCOUNT);
        }
        if (user == null) {
            user = new StudioUserEntity();
            user.setTenantId(StudioConstants.DEFAULT_TENANT_ID);
            user.setUsername(superAdminGatewayUser
                    ? SUPER_ADMIN_ACCOUNT
                    : resolveAvailableUsername(userInfo.getAccount(), userInfo.getUserId()));
            user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        }
        user.setDisplayName(resolveDisplayName(userInfo));
        boolean mobileProvided = userInfo.isMobilePhonePresent() || userInfo.isPhoneNumberPresent();
        String mobile = resolveMobile(userInfo);
        if (mobileProvided) {
            user.setMobilePhone(mobile);
        }
        user.setEnabled(1);
        user.setAuthSource(StudioConstants.AUTH_SOURCE_GATEWAY);

        if (user.getId() == null) {
            userMapper.insert(user);
        } else {
            userMapper.updateById(user);
            if (mobileProvided && mobile == null) {
                userMapper.update(null, new LambdaUpdateWrapper<StudioUserEntity>()
                        .eq(StudioUserEntity::getId, user.getId())
                        .set(StudioUserEntity::getMobilePhone, null));
            }
        }

        if (binding == null) {
            binding = new StudioExternalUserBindingEntity();
            binding.setTenantId(StudioConstants.DEFAULT_TENANT_ID);
            binding.setProviderCode(StudioConstants.GATEWAY_PROVIDER_CODE);
            binding.setExternalUserId(userInfo.getUserId());
            binding.setStudioUserId(user.getId());
        }
        binding.setExternalAccount(normalize(userInfo.getAccount()));
        binding.setLastSeenAt(LocalDateTime.now());
        if (binding.getId() == null) {
            externalUserBindingMapper.insert(binding);
        } else {
            externalUserBindingMapper.updateById(binding);
        }
        if (superAdminGatewayUser) {
            ensureSuperAdminRoles(user.getId());
        }
        user.setExternalAccount(binding.getExternalAccount());
        return user;
    }

    private String resolveMobile(GatewayUserInfo userInfo) {
        String mobile = StudioMobileSupport.normalize(userInfo.getMobilePhone());
        return mobile == null
                ? StudioMobileSupport.normalize(userInfo.getPhoneNumber())
                : mobile;
    }

    private boolean isSuperAdminGatewayUser(GatewayUserInfo userInfo) {
        return SUPER_ADMIN_ACCOUNT.equalsIgnoreCase(normalize(userInfo.getAccount()));
    }

    private StudioUserEntity selectUserByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<StudioUserEntity>()
                .eq(StudioUserEntity::getUsername, username)
                .last("limit 1"));
    }

    private void ensureSuperAdminRoles(Long userId) {
        RoleEntity adminRole = requireRole(StudioConstants.ROLE_ADMIN);
        RoleEntity superAdminRole = requireRole(StudioConstants.ROLE_SUPER_ADMIN);
        ensureUserRole(userId, adminRole.getId());
        ensureUserRole(userId, superAdminRole.getId());
    }

    private RoleEntity requireRole(String code) {
        RoleEntity role = roleMapper.selectOne(new LambdaQueryWrapper<RoleEntity>()
                .eq(RoleEntity::getCode, code)
                .last("limit 1"));
        if (role == null) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Studio role is missing: " + code);
        }
        return role;
    }

    private void ensureUserRole(Long userId, Long roleId) {
        Long count = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRoleEntity>()
                .eq(UserRoleEntity::getUserId, userId)
                .eq(UserRoleEntity::getRoleId, roleId));
        if (count != null && count.longValue() > 0L) {
            return;
        }
        UserRoleEntity userRole = new UserRoleEntity();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        userRoleMapper.insert(userRole);
    }

    private String resolveAvailableUsername(String externalAccount, String externalUserId) {
        String normalizedAccount = normalizeUsername(externalAccount);
        if (!hasText(normalizedAccount)) {
            normalizedAccount = "gateway_user_" + lastCharacters(externalUserId, 6);
        }
        String[] candidates = new String[] {
                normalizedAccount,
                "gateway_" + normalizedAccount,
                "gateway_" + normalizedAccount + "_" + lastCharacters(externalUserId, 6)
        };
        for (String candidate : candidates) {
            if (!usernameExists(candidate)) {
                return candidate;
            }
        }
        int suffix = 1;
        while (suffix < Integer.MAX_VALUE) {
            String candidate = candidates[candidates.length - 1] + "_" + suffix;
            if (!usernameExists(candidate)) {
                return candidate;
            }
            suffix++;
        }
        throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Failed to allocate gateway username");
    }

    private boolean usernameExists(String username) {
        if (!hasText(username)) {
            return true;
        }
        Long count = userMapper.selectCount(new LambdaQueryWrapper<StudioUserEntity>()
                .eq(StudioUserEntity::getUsername, username));
        return count != null && count.longValue() > 0L;
    }

    private String resolveDisplayName(GatewayUserInfo userInfo) {
        String displayName = normalize(userInfo.getUserName());
        if (displayName != null) {
            return displayName;
        }
        displayName = normalize(userInfo.getName());
        if (displayName != null) {
            return displayName;
        }
        displayName = normalize(userInfo.getAccount());
        if (displayName != null) {
            return displayName;
        }
        return "Gateway User " + lastCharacters(userInfo.getUserId(), 6);
    }

    private String normalizeUsername(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        return normalized.replaceAll("\\s+", "_");
    }

    private String lastCharacters(String value, int size) {
        String normalized = normalize(value);
        if (normalized == null) {
            return "user";
        }
        return normalized.length() <= size ? normalized : normalized.substring(normalized.length() - size);
    }

    private String sign(String sharedSecret, String rawUserInfo, String timestamp, String requestPath) {
        try {
            String payload = rawUserInfo + "\n" + timestamp + "\n" + requestPath;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Failed to verify gateway signature");
        }
    }

    private String requireValue(String value, String message) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, message);
        }
        return normalized;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean hasText(String value) {
        return normalize(value) != null;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GatewayUserInfo {
        private String userId;
        private String account;
        private String userName;
        private String name;
        private String phoneNumber;
        private String mobilePhone;

        @JsonIgnore
        private boolean phoneNumberPresent;

        @JsonIgnore
        private boolean mobilePhonePresent;

        public void setPhoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
            this.phoneNumberPresent = true;
        }

        public void setMobilePhone(String mobilePhone) {
            this.mobilePhone = mobilePhone;
            this.mobilePhonePresent = true;
        }
    }
}
