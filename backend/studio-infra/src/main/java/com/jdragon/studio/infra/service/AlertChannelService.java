package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.AlertChannelType;
import com.jdragon.studio.dto.model.AlertChannelView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.AlertChannelQueryRequest;
import com.jdragon.studio.dto.model.request.AlertChannelSaveRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.AlertChannelEntity;
import com.jdragon.studio.infra.entity.AlertRuleEntity;
import com.jdragon.studio.infra.mapper.AlertChannelMapper;
import com.jdragon.studio.infra.mapper.AlertRuleMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.dao.DuplicateKeyException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.TreeSet;

@Service
public class AlertChannelService {

    private static final int MAX_ENDPOINT_URL_LENGTH = 2048;
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_HEADER_COUNT = 32;
    private static final int MAX_HEADER_NAME_LENGTH = 128;
    private static final int MAX_HEADER_VALUE_LENGTH = 4000;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_SIGNING_SECRET_LENGTH = 4096;
    private static final Set<String> FORBIDDEN_HEADERS = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);

    static {
        Collections.addAll(FORBIDDEN_HEADERS, "Host", "Content-Length", "Connection", "Transfer-Encoding", "Upgrade", "Proxy-Connection",
                "Keep-Alive", "TE", "Trailer", "Expect", "Proxy-Authorization", "Proxy-Authenticate",
                "Content-Type", "Accept", "User-Agent", "X-Studio-Event-Id", "X-Studio-Timestamp", "X-Studio-Signature-SHA256");
    }

    private final AlertChannelMapper alertChannelMapper;
    private final AlertRuleMapper alertRuleMapper;
    private final AlertRuleService alertRuleService;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final EncryptionService encryptionService;
    private final AlertWebhookSecurityService webhookSecurityService;
    private final ObjectMapper objectMapper;
    private StudioPlatformProperties properties;

    public AlertChannelService(AlertChannelMapper alertChannelMapper,
                               AlertRuleMapper alertRuleMapper,
                               AlertRuleService alertRuleService,
                               StudioSecurityService securityService,
                               ProjectResourceAccessService projectResourceAccessService,
                               EncryptionService encryptionService,
                               AlertWebhookSecurityService webhookSecurityService,
                               ObjectMapper objectMapper) {
        this.alertChannelMapper = alertChannelMapper;
        this.alertRuleMapper = alertRuleMapper;
        this.alertRuleService = alertRuleService;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.encryptionService = encryptionService;
        this.webhookSecurityService = webhookSecurityService;
        this.objectMapper = objectMapper;
    }

    @Autowired
    void setStudioPlatformProperties(StudioPlatformProperties properties) {
        this.properties = properties;
    }

    public PageView<AlertChannelView> query(AlertChannelQueryRequest request) {
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        int pageNo = pageNo(request == null ? null : request.getPageNo());
        int pageSize = pageSize(request == null ? null : request.getPageSize());
        String keyword = request == null ? null : request.getKeyword();
        LambdaQueryWrapper<AlertChannelEntity> query = new LambdaQueryWrapper<AlertChannelEntity>()
                .eq(AlertChannelEntity::getTenantId, securityService.currentTenantId())
                .eq(AlertChannelEntity::getProjectId, projectId)
                .like(StringUtils.hasText(keyword), AlertChannelEntity::getName,
                        StringUtils.hasText(keyword) ? keyword.trim() : null)
                .eq(request != null && request.getEnabled() != null, AlertChannelEntity::getEnabled,
                        request != null && Boolean.TRUE.equals(request.getEnabled()) ? 1 : 0);
        Long total = alertChannelMapper.selectCount(query);
        List<AlertChannelEntity> entities = alertChannelMapper.selectList(query
                .orderByDesc(AlertChannelEntity::getEnabled)
                .orderByDesc(AlertChannelEntity::getUpdatedAt)
                .orderByDesc(AlertChannelEntity::getId)
                .last("limit " + ((pageNo - 1) * pageSize) + "," + pageSize));
        List<AlertChannelView> items = new ArrayList<AlertChannelView>();
        for (AlertChannelEntity entity : entities) {
            items.add(toView(entity));
        }
        return PageView.of(pageNo, pageSize, total == null ? 0L : total.longValue(), items);
    }

    public AlertChannelView get(Long id) {
        return toView(requireCurrentProjectChannel(id));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AlertChannelView save(AlertChannelSaveRequest request) {
        alertRuleService.requireManage();
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Alert channel payload is required");
        }
        String name = requireText(request.getName(), "Alert channel name is required");
        if (name.length() > MAX_NAME_LENGTH) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Alert channel name is too long");
        }
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        String tenantId = securityService.currentTenantId();
        assertUniqueName(name, request.getId(), tenantId, projectId);
        AlertChannelEntity entity = request.getId() == null ? new AlertChannelEntity() : requireCurrentProjectChannel(request.getId());
        String channelType = resolveChannelType(request.getChannelType(), entity);
        if (entity.getId() != null && StringUtils.hasText(entity.getChannelType())
                && !channelType.equalsIgnoreCase(entity.getChannelType())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Alert channel type cannot be changed");
        }
        if (AlertChannelType.WEBHOOK.name().equals(channelType)) {
            applyWebhookConfig(request, entity);
        } else {
            applyElinkConfig(request, entity);
        }
        entity.setTenantId(tenantId);
        entity.setProjectId(projectId);
        entity.setName(name);
        entity.setChannelType(channelType);
        boolean enabled = request.getEnabled() == null
                ? entity.getId() == null || Integer.valueOf(1).equals(entity.getEnabled())
                : Boolean.TRUE.equals(request.getEnabled());
        if (!enabled && Integer.valueOf(1).equals(entity.getEnabled())) {
            ensureRulesKeepDestination(entity.getId());
        }
        entity.setEnabled(enabled ? 1 : 0);
        entity.setUpdatedBy(securityService.currentUserId());
        try {
            if (entity.getId() == null) {
                entity.setCreatedBy(securityService.currentUserId());
                alertChannelMapper.insert(entity);
            } else {
                alertChannelMapper.updateById(entity);
                if (AlertChannelType.ELINK.name().equals(channelType)) {
                    clearLegacyElinkTransportFields(entity.getId());
                }
            }
        } catch (DuplicateKeyException ex) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR, "通知通道名称已存在");
        }
        return toView(entity);
    }

    @Transactional
    public AlertChannelView enable(Long id) {
        alertRuleService.requireManage();
        AlertChannelEntity entity = requireCurrentProjectChannel(id);
        entity.setEnabled(1);
        entity.setUpdatedBy(securityService.currentUserId());
        alertChannelMapper.updateById(entity);
        return toView(entity);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AlertChannelView disable(Long id) {
        alertRuleService.requireManage();
        AlertChannelEntity entity = requireCurrentProjectChannel(id);
        ensureRulesKeepDestination(entity.getId());
        entity.setEnabled(0);
        entity.setUpdatedBy(securityService.currentUserId());
        alertChannelMapper.updateById(entity);
        return toView(entity);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void delete(Long id) {
        alertRuleService.requireManage();
        AlertChannelEntity entity = requireCurrentProjectChannel(id);
        List<AlertRuleEntity> rules = currentProjectRules();
        for (AlertRuleEntity rule : rules) {
            if (rule.getWebhookChannelIdsJson() != null && rule.getWebhookChannelIdsJson().contains(entity.getId())) {
                throw new StudioException(StudioErrorCode.BUSINESS_ERROR, "Detach this channel from alert rules before deleting it");
            }
        }
        alertChannelMapper.deleteById(entity.getId());
    }

    public AlertChannelEntity requireCurrentProjectChannel(Long id) {
        if (id == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Alert channel id is required");
        }
        AlertChannelEntity entity = alertChannelMapper.selectOne(new LambdaQueryWrapper<AlertChannelEntity>()
                .eq(AlertChannelEntity::getId, id)
                .eq(AlertChannelEntity::getTenantId, securityService.currentTenantId())
                .eq(AlertChannelEntity::getProjectId, projectResourceAccessService.requireCurrentProjectId())
                .last("limit 1"));
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Alert channel was not found");
        }
        return entity;
    }

    public AlertChannelEntity findById(Long id, String tenantId, Long projectId) {
        if (id == null || !StringUtils.hasText(tenantId) || projectId == null) {
            return null;
        }
        return alertChannelMapper.selectOne(new LambdaQueryWrapper<AlertChannelEntity>()
                .eq(AlertChannelEntity::getId, id)
                .eq(AlertChannelEntity::getTenantId, tenantId)
                .eq(AlertChannelEntity::getProjectId, projectId)
                .last("limit 1"));
    }

    public String endpoint(AlertChannelEntity channel) {
        return channel == null || !StringUtils.hasText(channel.getEndpointCiphertext())
                ? null : encryptionService.decrypt(channel.getEndpointCiphertext());
    }

    public Map<String, String> headers(AlertChannelEntity channel) {
        if (channel == null || !StringUtils.hasText(channel.getHeadersCiphertext())) {
            return new LinkedHashMap<String, String>();
        }
        try {
            return objectMapper.readValue(encryptionService.decrypt(channel.getHeadersCiphertext()),
                    new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR, "Stored webhook headers cannot be decrypted");
        }
    }

    public String signingSecret(AlertChannelEntity channel) {
        return channel == null || !StringUtils.hasText(channel.getSigningSecretCiphertext())
                ? null : encryptionService.decrypt(channel.getSigningSecretCiphertext());
    }

    public String elinkTargetType(AlertChannelEntity channel) {
        return configText(channel == null ? null : channel.getConfigJson(), "targetType");
    }

    public List<String> elinkUserIds(AlertChannelEntity channel) {
        return configStrings(channel == null ? null : channel.getConfigJson(), "userIds");
    }

    public Long elinkGroupId(AlertChannelEntity channel) {
        return configLong(channel == null ? null : channel.getConfigJson(), "groupId").orElse(null);
    }

    public void markTestResult(Long channelId, String status, String message) {
        if (channelId == null) {
            return;
        }
        alertChannelMapper.update(null, new LambdaUpdateWrapper<AlertChannelEntity>()
                .eq(AlertChannelEntity::getId, channelId)
                .set(AlertChannelEntity::getLastTestedAt, LocalDateTime.now())
                .set(AlertChannelEntity::getLastTestStatus, status)
                .set(AlertChannelEntity::getLastTestMessage, truncate(message, 1000)));
    }

    public AlertChannelView toView(AlertChannelEntity entity) {
        AlertChannelView view = new AlertChannelView();
        view.setId(entity.getId());
        view.setName(entity.getName());
        view.setChannelType(entity.getChannelType());
        if (AlertChannelType.ELINK.name().equals(entity.getChannelType())) {
            // Legacy eLink rows may still contain fields from the discarded SLB design.
            view.setEndpointMasked(null);
            view.setHeaderNames(new ArrayList<String>());
            view.setHasSigningSecret(false);
        } else {
            view.setEndpointMasked(maskEndpoint(endpoint(entity)));
            view.setHeaderNames(new ArrayList<String>(headers(entity).keySet()));
            view.setHasSigningSecret(StringUtils.hasText(entity.getSigningSecretCiphertext()));
        }
        Map<String, Object> config = entity.getConfigJson();
        view.setElinkTargetType(configText(config, "targetType"));
        view.setElinkUserIds(configStrings(config, "userIds"));
        view.setElinkGroupId(configLong(config, "groupId").orElse(null));
        view.setEnabled(Integer.valueOf(1).equals(entity.getEnabled()));
        view.setLastTestedAt(entity.getLastTestedAt());
        view.setLastTestStatus(entity.getLastTestStatus());
        view.setLastTestMessage(entity.getLastTestMessage());
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        return view;
    }

    private void ensureRulesKeepDestination(Long disabledChannelId) {
        for (AlertRuleEntity rule : currentProjectRules()) {
            if (!Integer.valueOf(1).equals(rule.getEnabled()) || rule.getWebhookChannelIdsJson() == null
                    || !rule.getWebhookChannelIdsJson().contains(disabledChannelId)) {
                continue;
            }
            boolean hasInApp = alertRuleService.hasEffectiveInAppDestination(rule);
            List<Long> otherChannelIds = new ArrayList<Long>();
            for (Long channelId : rule.getWebhookChannelIdsJson()) {
                if (channelId != null && !channelId.equals(disabledChannelId)) {
                    otherChannelIds.add(channelId);
                }
            }
            Long otherWebhookCount = otherChannelIds.isEmpty() ? 0L
                    : alertChannelMapper.selectCount(new LambdaQueryWrapper<AlertChannelEntity>()
                    .eq(AlertChannelEntity::getTenantId, rule.getTenantId())
                    .eq(AlertChannelEntity::getProjectId, rule.getProjectId())
                    .eq(AlertChannelEntity::getEnabled, 1)
                    .in(AlertChannelEntity::getId, otherChannelIds));
            boolean hasOtherWebhook = otherWebhookCount != null && otherWebhookCount.longValue() > 0L;
            if (!hasInApp && !hasOtherWebhook) {
                throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                        "This channel is the last active destination for rule " + rule.getName());
            }
        }
    }

    private List<AlertRuleEntity> currentProjectRules() {
        return alertRuleMapper.selectList(new LambdaQueryWrapper<AlertRuleEntity>()
                .eq(AlertRuleEntity::getTenantId, securityService.currentTenantId())
                .eq(AlertRuleEntity::getProjectId, projectResourceAccessService.requireCurrentProjectId()));
    }

    private void assertUniqueName(String name, Long excludedId, String tenantId, Long projectId) {
        Long count = alertChannelMapper.selectCount(new LambdaQueryWrapper<AlertChannelEntity>()
                .eq(AlertChannelEntity::getTenantId, tenantId)
                .eq(AlertChannelEntity::getProjectId, projectId)
                .eq(AlertChannelEntity::getName, name)
                .ne(excludedId != null, AlertChannelEntity::getId, excludedId));
        if (count != null && count.longValue() > 0L) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR, "通知通道名称已存在");
        }
    }

    private String resolveChannelType(String requestedType, AlertChannelEntity entity) {
        String value = StringUtils.hasText(requestedType) ? requestedType.trim().toUpperCase(Locale.ROOT)
                : entity != null && StringUtils.hasText(entity.getChannelType())
                ? entity.getChannelType().trim().toUpperCase(Locale.ROOT) : AlertChannelType.WEBHOOK.name();
        if (!AlertChannelType.WEBHOOK.name().equals(value) && !AlertChannelType.ELINK.name().equals(value)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported alert channel type: " + value);
        }
        return value;
    }

    private void applyWebhookConfig(AlertChannelSaveRequest request, AlertChannelEntity entity) {
        if (entity.getId() == null && !StringUtils.hasText(request.getEndpointUrl())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Webhook endpoint URL is required");
        }
        URI endpoint = null;
        if (StringUtils.hasText(request.getEndpointUrl())) {
            if (request.getEndpointUrl().trim().length() > MAX_ENDPOINT_URL_LENGTH) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Webhook endpoint URL is too long");
            }
            endpoint = webhookSecurityService.validate(request.getEndpointUrl());
        }
        Map<String, String> normalizedHeaders = request.getHeaders() == null
                ? null : normalizeHeaders(request.getHeaders());
        if (Boolean.TRUE.equals(request.getClearSigningSecret()) && StringUtils.hasText(request.getSigningSecret())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Signing secret cannot be supplied while requesting it to be cleared");
        }
        if (request.getSigningSecret() != null && request.getSigningSecret().length() > MAX_SIGNING_SECRET_LENGTH) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Webhook signing secret is too long");
        }
        if (endpoint != null) {
            entity.setEndpointCiphertext(encryptionService.encrypt(endpoint.toString()));
        }
        if (normalizedHeaders != null) {
            entity.setHeadersCiphertext(encryptionService.encrypt(writeJson(normalizedHeaders)));
        } else if (entity.getId() == null) {
            entity.setHeadersCiphertext(encryptionService.encrypt("{}"));
        }
        if (Boolean.TRUE.equals(request.getClearSigningSecret())) {
            entity.setSigningSecretCiphertext(null);
        } else if (StringUtils.hasText(request.getSigningSecret())) {
            entity.setSigningSecretCiphertext(encryptionService.encrypt(request.getSigningSecret()));
        }
        entity.setConfigJson(new LinkedHashMap<String, Object>());
    }

    private void applyElinkConfig(AlertChannelSaveRequest request, AlertChannelEntity entity) {
        if (!elinkChannelEnabled()) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "eLink alert delivery is not enabled on this Studio runtime");
        }
        if (StringUtils.hasText(request.getEndpointUrl()) || request.getHeaders() != null
                || StringUtils.hasText(request.getSigningSecret()) || Boolean.TRUE.equals(request.getClearSigningSecret())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "eLink channels do not accept Webhook endpoint, Header, or signing settings");
        }
        String targetType = requireText(request.getElinkTargetType(), "eLink target type is required")
                .toUpperCase(Locale.ROOT);
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("targetType", targetType);
        if ("PERSONAL".equals(targetType)) {
            List<String> userIds = normalizeElinkUserIds(request.getElinkUserIds());
            if (userIds.isEmpty()) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "At least one eLink account is required");
            }
            config.put("userIds", userIds);
        } else if ("GROUP".equals(targetType)) {
            if (request.getElinkGroupId() == null || request.getElinkGroupId().longValue() <= 0L) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "A positive eLink group id is required");
            }
            config.put("groupId", request.getElinkGroupId());
        } else {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Unsupported eLink target type: " + targetType);
        }
        entity.setEndpointCiphertext(null);
        entity.setHeadersCiphertext(null);
        entity.setSigningSecretCiphertext(null);
        entity.setConfigJson(config);
    }

    private boolean elinkChannelEnabled() {
        return properties == null || properties.getAlert() == null || properties.getAlert().getElink() == null
                || properties.getAlert().getElink().isEnabled();
    }

    private void clearLegacyElinkTransportFields(Long channelId) {
        alertChannelMapper.update(null, new LambdaUpdateWrapper<AlertChannelEntity>()
                .eq(AlertChannelEntity::getId, channelId)
                .set(AlertChannelEntity::getEndpointCiphertext, null)
                .set(AlertChannelEntity::getHeadersCiphertext, null)
                .set(AlertChannelEntity::getSigningSecretCiphertext, null));
    }

    private List<String> normalizeElinkUserIds(List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        if (values != null) {
            for (String value : values) {
                String normalized = requireText(value, "eLink account must not be blank");
                if (normalized.length() > 128 || normalized.indexOf('|') >= 0
                        || "@all".equalsIgnoreCase(normalized)) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST,
                            "Invalid eLink account: " + normalized);
                }
                unique.add(normalized);
            }
        }
        if (unique.size() > 1000) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "eLink accounts must not contain more than 1000 entries");
        }
        return new ArrayList<String>(unique);
    }

    private String configText(Map<String, Object> config, String key) {
        Object value = config == null ? null : config.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private List<String> configStrings(Map<String, Object> config, String key) {
        Object value = config == null ? null : config.get(key);
        List<String> result = new ArrayList<String>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }

    private Optional<Long> configLong(Map<String, Object> config, String key) {
        Object value = config == null ? null : config.get(key);
        if (value instanceof Number number) {
            return Optional.of(number.longValue());
        }
        try {
            return value == null ? Optional.empty() : Optional.of(Long.valueOf(String.valueOf(value)));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private Map<String, String> normalizeHeaders(Map<String, String> headers) {
        if (headers.size() > MAX_HEADER_COUNT) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Webhook headers must not contain more than " + MAX_HEADER_COUNT + " entries");
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        Set<String> normalizedNames = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        int totalBytes = 0;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = requireText(entry.getKey(), "Webhook header name must not be blank");
            if (name.length() > MAX_HEADER_NAME_LENGTH
                    || !name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+") || FORBIDDEN_HEADERS.contains(name)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Webhook header is not allowed: " + name);
            }
            if (!normalizedNames.add(name)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Webhook header is duplicated: " + name);
            }
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (value.contains("\r") || value.contains("\n") || value.length() > MAX_HEADER_VALUE_LENGTH) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Webhook header value is invalid: " + name);
            }
            totalBytes += name.getBytes(StandardCharsets.UTF_8).length;
            totalBytes += value.getBytes(StandardCharsets.UTF_8).length;
            if (totalBytes > MAX_HEADER_BYTES) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Webhook headers exceed the maximum total size");
            }
            result.put(name, value);
        }
        return result;
    }

    private String maskEndpoint(String endpoint) {
        if (!StringUtils.hasText(endpoint)) {
            return null;
        }
        try {
            URI uri = URI.create(endpoint);
            String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
            String host = uri.getHost();
            if (host != null && host.contains(":")) {
                host = "[" + host + "]";
            }
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + host + port + "/***";
        } catch (Exception ex) {
            return "***";
        }
    }

    private String writeJson(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Webhook headers cannot be serialized");
        }
    }

    private int pageNo(Integer value) {
        return value == null || value.intValue() < 1 ? 1 : value.intValue();
    }

    private int pageSize(Integer value) {
        return Math.min(value == null || value.intValue() < 1 ? 20 : value.intValue(), 100);
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
