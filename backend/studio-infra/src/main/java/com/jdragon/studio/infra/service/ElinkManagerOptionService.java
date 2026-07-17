package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.ElinkGroupOptionView;
import com.jdragon.studio.dto.model.ElinkUserOptionView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@Service
public class ElinkManagerOptionService {

    private static final long CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final ElinkManagerEndpointResolver endpointResolver;
    private final StudioPlatformProperties properties;
    private final ObjectMapper objectMapper;
    private final StudioSecurityService securityService;
    private final HttpClient httpClient;
    private final LongSupplier nanoTime;
    private final Object userCacheMonitor = new Object();
    private final Object groupCacheMonitor = new Object();
    private volatile CacheEntry<ElinkUserOptionView> userCache = CacheEntry.empty();
    private volatile CacheEntry<ElinkGroupOptionView> groupCache = CacheEntry.empty();

    @Autowired
    public ElinkManagerOptionService(ElinkManagerEndpointResolver endpointResolver,
                                     StudioPlatformProperties properties,
                                     ObjectMapper objectMapper,
                                     StudioSecurityService securityService) {
        this(endpointResolver, properties, objectMapper, securityService, System::nanoTime);
    }

    ElinkManagerOptionService(ElinkManagerEndpointResolver endpointResolver,
                              StudioPlatformProperties properties,
                              ObjectMapper objectMapper,
                              StudioSecurityService securityService,
                              LongSupplier nanoTime) {
        this.endpointResolver = endpointResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.securityService = securityService;
        this.nanoTime = nanoTime;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(positive(settings().getConnectTimeoutSeconds(), 3)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public PageView<ElinkUserOptionView> users(String keyword, Integer pageNoValue, Integer pageSizeValue) {
        requireManage();
        return page(filterUsers(loadUsers(), keyword), pageNoValue, pageSizeValue);
    }

    public PageView<ElinkGroupOptionView> groups(String keyword, Integer pageNoValue, Integer pageSizeValue) {
        requireManage();
        return page(filterGroups(loadGroups(), keyword), pageNoValue, pageSizeValue);
    }

    public ElinkUserOptionView requireUser(String userId) {
        String requiredUserId = requireText(userId, "eLink account is required");
        for (ElinkUserOptionView user : loadUsers()) {
            if (requiredUserId.equals(user.getUserId())) {
                if (Boolean.FALSE.equals(user.getEnabled())) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST,
                            "eLink account is disabled: " + requiredUserId);
                }
                return user;
            }
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST,
                "eLink account was not found: " + requiredUserId);
    }

    public ElinkGroupOptionView requireGroup(Long id) {
        if (id == null || id.longValue() <= 0L) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "eLink group id is required");
        }
        for (ElinkGroupOptionView group : loadGroups()) {
            if (id.equals(group.getId())) {
                return group;
            }
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "eLink group was not found: " + id);
    }

    private List<ElinkUserOptionView> loadUsers() {
        long now = nanoTime.getAsLong();
        CacheEntry<ElinkUserOptionView> current = userCache;
        if (current.validAt(now)) {
            return current.items;
        }
        synchronized (userCacheMonitor) {
            now = nanoTime.getAsLong();
            current = userCache;
            if (!current.validAt(now)) {
                List<ElinkUserOptionView> loaded = List.copyOf(fetchUsers());
                current = new CacheEntry<ElinkUserOptionView>(loaded, expiresAt(now));
                userCache = current;
            }
            return current.items;
        }
    }

    private List<ElinkGroupOptionView> loadGroups() {
        long now = nanoTime.getAsLong();
        CacheEntry<ElinkGroupOptionView> current = groupCache;
        if (current.validAt(now)) {
            return current.items;
        }
        synchronized (groupCacheMonitor) {
            now = nanoTime.getAsLong();
            current = groupCache;
            if (!current.validAt(now)) {
                List<ElinkGroupOptionView> loaded = List.copyOf(fetchGroups());
                current = new CacheEntry<ElinkGroupOptionView>(loaded, expiresAt(now));
                groupCache = current;
            }
            return current.items;
        }
    }

    private long expiresAt(long now) {
        long expiresAt = now + CACHE_TTL_NANOS;
        return expiresAt < now ? Long.MAX_VALUE : expiresAt;
    }

    private List<ElinkUserOptionView> fetchUsers() {
        JsonNode root = get("/app/allow-users");
        JsonNode users = root.path("users");
        if (!users.isArray()) {
            throw invalidResponse();
        }
        Map<String, ElinkUserOptionView> unique = new LinkedHashMap<String, ElinkUserOptionView>();
        for (JsonNode node : users) {
            String userId = trimToNull(text(node, "userid", "userId"));
            if (userId == null) {
                continue;
            }
            ElinkUserOptionView option = new ElinkUserOptionView();
            option.setUserId(userId);
            option.setName(firstText(trimToNull(text(node, "name")), userId));
            option.setEnabled(enabled(node));
            unique.put(userId, option);
        }
        List<ElinkUserOptionView> result = new ArrayList<ElinkUserOptionView>(unique.values());
        result.sort(Comparator.comparing(ElinkUserOptionView::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ElinkUserOptionView::getUserId));
        return result;
    }

    private List<ElinkGroupOptionView> fetchGroups() {
        JsonNode root = get("/groups");
        if (!root.isArray()) {
            throw invalidResponse();
        }
        Map<Long, ElinkGroupOptionView> unique = new LinkedHashMap<Long, ElinkGroupOptionView>();
        for (JsonNode node : root) {
            Long id = positiveLong(node.get("id"));
            if (id == null) {
                continue;
            }
            ElinkGroupOptionView option = new ElinkGroupOptionView();
            option.setId(id);
            option.setName(firstText(trimToNull(text(node, "name")), String.valueOf(id)));
            JsonNode members = node.get("members");
            option.setMemberCount(members != null && members.isArray()
                    ? Integer.valueOf(members.size()) : nonNegativeInteger(node.get("memberCount")));
            unique.put(id, option);
        }
        List<ElinkGroupOptionView> result = new ArrayList<ElinkGroupOptionView>(unique.values());
        result.sort(Comparator.comparing(ElinkGroupOptionView::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ElinkGroupOptionView::getId));
        return result;
    }

    private JsonNode get(String relativePath) {
        if (!settings().isEnabled()) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR, "eLink alert delivery is disabled");
        }
        try {
            URI endpoint = endpointResolver.resolve(relativePath);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(positive(settings().getRequestTimeoutSeconds(), 10)))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream input = response.body()) {
                body = readLimited(input, maxOptionResponseBytes());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw managerFailure(response.statusCode(), body);
            }
            JsonNode root = objectMapper.readTree(body);
            if (root == null) {
                throw invalidResponse();
            }
            String businessError = managerBusinessError(root);
            if (businessError != null) {
                throw new StudioException(StudioErrorCode.BUSINESS_ERROR, sanitize(businessError));
            }
            return root;
        } catch (StudioException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw managerUnavailable(ex);
        } catch (IOException ex) {
            throw managerUnavailable(ex);
        } catch (RuntimeException ex) {
            throw managerUnavailable(ex);
        }
    }

    private byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        byte[] bytes = input.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "eLink Manager response exceeds the configured size limit");
        }
        return bytes;
    }

    private StudioException managerFailure(int status, byte[] body) {
        String error = extractManagerError(body);
        if (!StringUtils.hasText(error)) {
            error = "eLink Manager returned HTTP " + status;
        }
        return new StudioException(StudioErrorCode.BUSINESS_ERROR, sanitize(error));
    }

    private String extractManagerError(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            String error = firstTextNode(node, "errorMessage", "message", "errmsg", "detail", "error");
            return StringUtils.hasText(error) ? error : new String(body, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private String managerBusinessError(JsonNode root) {
        JsonNode success = root.get("success");
        JsonNode errcode = root.get("errcode");
        boolean failed = success != null && success.isBoolean() && !success.booleanValue();
        failed = failed || errcode != null && errcode.canConvertToInt() && errcode.intValue() != 0;
        return failed ? firstText(firstTextNode(root, "errorMessage", "message", "errmsg", "detail", "error"),
                errcode == null ? "eLink Manager returned an unsuccessful response"
                        : "eLink Manager returned errcode " + errcode.asText()) : null;
    }

    private StudioException managerUnavailable(Exception ex) {
        String message = sanitize(ex.getMessage());
        if (!StringUtils.hasText(message)) {
            message = ex.getClass().getSimpleName();
        }
        return new StudioException(StudioErrorCode.BUSINESS_ERROR, message, ex);
    }

    private StudioException invalidResponse() {
        return new StudioException(StudioErrorCode.BUSINESS_ERROR,
                "eLink Manager returned an invalid response");
    }

    private List<ElinkUserOptionView> filterUsers(List<ElinkUserOptionView> users, String keyword) {
        String normalized = normalizedKeyword(keyword);
        if (normalized == null) {
            return users;
        }
        List<ElinkUserOptionView> result = new ArrayList<ElinkUserOptionView>();
        for (ElinkUserOptionView user : users) {
            if (contains(user.getUserId(), normalized) || contains(user.getName(), normalized)) {
                result.add(user);
            }
        }
        return result;
    }

    private List<ElinkGroupOptionView> filterGroups(List<ElinkGroupOptionView> groups, String keyword) {
        String normalized = normalizedKeyword(keyword);
        if (normalized == null) {
            return groups;
        }
        List<ElinkGroupOptionView> result = new ArrayList<ElinkGroupOptionView>();
        for (ElinkGroupOptionView group : groups) {
            if (contains(group.getName(), normalized) || contains(String.valueOf(group.getId()), normalized)) {
                result.add(group);
            }
        }
        return result;
    }

    private <T> PageView<T> page(List<T> items, Integer pageNoValue, Integer pageSizeValue) {
        int pageNo = pageNoValue == null || pageNoValue.intValue() < 1 ? 1 : pageNoValue.intValue();
        int pageSize = Math.min(pageSizeValue == null || pageSizeValue.intValue() < 1
                ? 20 : pageSizeValue.intValue(), 100);
        long offset = (long) (pageNo - 1) * pageSize;
        int from = offset >= items.size() ? items.size() : (int) offset;
        int to = Math.min(from + pageSize, items.size());
        return PageView.of(pageNo, pageSize, items.size(), new ArrayList<T>(items.subList(from, to)));
    }

    private void requireManage() {
        if (!securityService.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN,
                StudioConstants.ROLE_TENANT_ADMIN, StudioConstants.ROLE_PROJECT_ADMIN)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN,
                    "Alert channel administration permission is required");
        }
    }

    private Boolean enabled(JsonNode node) {
        JsonNode value = node.has("enable") ? node.get("enable") : node.get("enabled");
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return Boolean.valueOf(value.booleanValue());
        }
        if (value.isNumber()) {
            return Boolean.valueOf(value.intValue() != 0);
        }
        String text = value.asText();
        if ("1".equals(text) || "true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("0".equals(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private Long positiveLong(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            long value = node.isNumber() ? node.longValue() : Long.parseLong(node.asText());
            return value > 0L ? Long.valueOf(value) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer nonNegativeInteger(JsonNode node) {
        if (node == null || !node.canConvertToInt()) {
            return Integer.valueOf(0);
        }
        return Integer.valueOf(Math.max(0, node.intValue()));
    }

    private String text(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode() && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private String firstTextNode(JsonNode node, String... fields) {
        return text(node, fields);
    }

    private String normalizedKeyword(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private boolean contains(String value, String normalizedKeyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedKeyword);
    }

    private String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first : fallback;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String requireText(String value, String message) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
        }
        return trimmed;
    }

    private String sanitize(String value) {
        return StringUtils.hasText(value) ? AlertSensitiveTextSanitizer.sanitize(value) : value;
    }

    private int maxOptionResponseBytes() {
        return Math.max(1024, positive(settings().getMaxOptionResponseBytes(), 1024 * 1024));
    }

    private int positive(Integer value, int fallback) {
        return value == null || value.intValue() < 1 ? fallback : value.intValue();
    }

    private StudioPlatformProperties.ElinkProperties settings() {
        return properties.getAlert().getElink();
    }

    private static final class CacheEntry<T> {
        private final List<T> items;
        private final long expiresAt;

        private CacheEntry(List<T> items, long expiresAt) {
            this.items = items;
            this.expiresAt = expiresAt;
        }

        private static <T> CacheEntry<T> empty() {
            return new CacheEntry<T>(List.of(), Long.MIN_VALUE);
        }

        private boolean validAt(long now) {
            return now < expiresAt;
        }
    }
}
