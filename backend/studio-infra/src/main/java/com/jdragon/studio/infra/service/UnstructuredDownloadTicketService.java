package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.UnstructuredDownloadTicketView;
import com.jdragon.studio.dto.model.request.UnstructuredDownloadTicketRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class UnstructuredDownloadTicketService {

    private static final Logger log = LoggerFactory.getLogger(UnstructuredDownloadTicketService.class);
    private static final String KEY_PREFIX = "studio:unstructured-download-ticket:";
    private static final Pattern TICKET_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final int RANDOM_BYTES = 32;
    private static final int MAX_GENERATION_ATTEMPTS = 3;

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectMapper objectMapper;
    private final UnstructuredManagementService managementService;
    private final StudioSecurityService securityService;
    private final StudioExecutionContextService executionContextService;
    private final StudioPlatformProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public UnstructuredDownloadTicketService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                             ObjectMapper objectMapper,
                                             UnstructuredManagementService managementService,
                                             StudioSecurityService securityService,
                                             StudioExecutionContextService executionContextService,
                                             StudioPlatformProperties properties) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.objectMapper = objectMapper;
        this.managementService = managementService;
        this.securityService = securityService;
        this.executionContextService = executionContextService;
        this.properties = properties;
    }

    public UnstructuredDownloadTicketView create(UnstructuredDownloadTicketRequest request) {
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Download ticket request is required");
        }
        UnstructuredManagementService.PreparedNativeDownload prepared =
                managementService.prepareNativeDownload(request.getRuntimeClusterId(),
                        request.getDatasourceId(), request.getPaths());
        Long userId = securityService.currentUserId();
        Long projectId = securityService.currentProjectId();
        String tenantId = securityService.currentTenantId();
        if (userId == null) {
            throw new StudioException(StudioErrorCode.UNAUTHORIZED, "Authentication required");
        }
        if (projectId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Project context is required");
        }

        long ttlSeconds = ticketTtlSeconds();
        Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
        TicketPayload payload = new TicketPayload();
        payload.userId = userId;
        payload.username = securityService.currentUsername();
        payload.tenantId = tenantId;
        payload.projectId = projectId;
        payload.runtimeClusterId = request.getRuntimeClusterId();
        payload.datasourceId = request.getDatasourceId();
        payload.paths = new ArrayList<String>(prepared.paths());
        payload.mode = prepared.archive() ? "ARCHIVE" : "FILE";
        payload.fileName = prepared.fileName();
        payload.expiresAtEpochMillis = expiresAt.toEpochMilli();

        String rawTicket = store(payload, Duration.ofSeconds(ttlSeconds));
        UnstructuredDownloadTicketView view = new UnstructuredDownloadTicketView();
        view.setTicket(rawTicket);
        view.setFileName(prepared.fileName());
        view.setArchive(prepared.archive());
        view.setContentLength(prepared.archive() ? null : prepared.contentLength());
        view.setExpiresAt(expiresAt);
        return view;
    }

    public UnstructuredManagementService.PreparedNativeDownload consume(String rawTicket) {
        if (rawTicket == null || !TICKET_PATTERN.matcher(rawTicket).matches()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Download ticket is malformed");
        }
        StringRedisTemplate redisTemplate = requireRedis();
        String serialized;
        try {
            serialized = redisTemplate.opsForValue().getAndDelete(key(rawTicket));
        } catch (RuntimeException exception) {
            log.warn("Unable to atomically consume an unstructured download ticket", exception);
            throw redisUnavailable();
        }
        if (serialized == null || serialized.isBlank()) {
            throw invalidTicket();
        }

        TicketPayload payload;
        try {
            payload = objectMapper.readValue(serialized, TicketPayload.class);
        } catch (Exception exception) {
            log.warn("Unable to deserialize an unstructured download ticket payload", exception);
            throw invalidTicket();
        }
        validate(payload);
        UnstructuredManagementService.PreparedNativeDownload prepared = executionContextService.callAs(
                payload.userId, payload.tenantId, payload.projectId,
                () -> managementService.prepareNativeDownload(payload.runtimeClusterId,
                        payload.datasourceId, payload.paths));
        boolean expectedArchive = "ARCHIVE".equals(payload.mode);
        if (prepared.archive() != expectedArchive) {
            throw new StudioException(StudioErrorCode.CONFLICT,
                    "Download target type changed after the ticket was created");
        }
        return prepared;
    }

    private String store(TicketPayload payload, Duration ttl) {
        StringRedisTemplate redisTemplate = requireRedis();
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Download ticket could not be serialized", exception);
        }
        try {
            for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
                String rawTicket = generateTicket();
                Boolean stored = redisTemplate.opsForValue().setIfAbsent(key(rawTicket), serialized, ttl);
                if (Boolean.TRUE.equals(stored)) {
                    return rawTicket;
                }
            }
        } catch (RuntimeException exception) {
            log.warn("Unable to store an unstructured download ticket", exception);
            throw redisUnavailable();
        }
        throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                "Download ticket could not be generated");
    }

    private StringRedisTemplate requireRedis() {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            throw redisUnavailable();
        }
        return redisTemplate;
    }

    private void validate(TicketPayload payload) {
        if (payload == null || payload.userId == null || payload.projectId == null
                || payload.tenantId == null || payload.tenantId.isBlank()
                || payload.runtimeClusterId == null || payload.datasourceId == null
                || payload.paths == null || payload.paths.isEmpty()
                || !("FILE".equals(payload.mode) || "ARCHIVE".equals(payload.mode))
                || payload.expiresAtEpochMillis <= System.currentTimeMillis()) {
            throw invalidTicket();
        }
    }

    private long ticketTtlSeconds() {
        Integer configured = properties.getFileTransfer().getDownloadTicketTtlSeconds();
        return configured == null || configured <= 0 ? 120L : configured.longValue();
    }

    private String generateTicket() {
        byte[] bytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String key(String rawTicket) {
        return KEY_PREFIX + sha256(rawTicket);
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash a download ticket", exception);
        }
    }

    private StudioException invalidTicket() {
        return new StudioException(StudioErrorCode.UNAUTHORIZED,
                "Download ticket is invalid, expired, or already used");
    }

    private StudioException redisUnavailable() {
        return new StudioException(StudioErrorCode.SERVICE_UNAVAILABLE,
                "Download ticket service is unavailable");
    }

    public static class TicketPayload {
        public Long userId;
        public String username;
        public String tenantId;
        public Long projectId;
        public Long runtimeClusterId;
        public Long datasourceId;
        public List<String> paths;
        public String mode;
        public String fileName;
        public long expiresAtEpochMillis;
    }
}
