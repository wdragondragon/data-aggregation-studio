package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;
import com.jdragon.studio.dto.model.FlinkQuestionResultView;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.dto.model.SqlStatementExecutionResultView;
import com.jdragon.studio.dto.model.request.FlinkSqlExecuteRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.service.script.DataDevelopmentExecutionContext;
import com.jdragon.studio.infra.service.script.DataDevelopmentScriptExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FlinkQuestionSqlDataDevelopmentExecutor implements DataDevelopmentScriptExecutor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };

    private final StudioPlatformProperties properties;
    private final ObjectMapper objectMapper;
    private final JwtTokenService jwtTokenService;
    private final StudioUserMapper studioUserMapper;
    private final HttpClient httpClient;

    public FlinkQuestionSqlDataDevelopmentExecutor(StudioPlatformProperties properties,
                                                   ObjectMapper objectMapper,
                                                   JwtTokenService jwtTokenService,
                                                   StudioUserMapper studioUserMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jwtTokenService = jwtTokenService;
        this.studioUserMapper = studioUserMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(normalizeConnectTimeoutSeconds(properties)))
                .build();
    }

    @Override
    public ScriptType getScriptType() {
        return ScriptType.FLINK_QUESTION_SQL;
    }

    @Override
    public DataScriptExecutionResultView execute(DataDevelopmentExecutionContext context) {
        long startedAt = System.currentTimeMillis();
        FlinkQuestionResultView flinkResult = executeRemote(context);
        SqlExecutionResultView sqlResult = toSqlResult(flinkResult, context, startedAt);

        DataScriptExecutionResultView result = new DataScriptExecutionResultView();
        result.setScriptType(ScriptType.FLINK_QUESTION_SQL);
        result.setSuccess(Boolean.TRUE);
        result.setStatus("SUCCESS");
        result.setMessage(sqlResult.getMessage());
        result.setExecutionMs(sqlResult.getExecutionMs());
        result.setLogs(String.join("\n", safeWarnings(flinkResult)));
        result.setSqlResult(sqlResult);
        result.setResultJson(toResultJson(flinkResult));
        return result;
    }

    private FlinkQuestionResultView executeRemote(DataDevelopmentExecutionContext context) {
        FlinkSqlExecuteRequest request = new FlinkSqlExecuteRequest();
        request.setSql(context.getContent());
        request.setMaxRows(context.getMaxRows());
        request.setModelIds(resolveModelIds(context));
        request.setScanMaxRows(resolveInteger(context, "scanMaxRows"));
        if (request.getModelIds().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "modelIds are required for 模型 Flink SQL execution");
        }
        try {
            String body = objectMapper.writeValueAsString(request);
            HttpRequest.Builder builder = HttpRequest.newBuilder(resolveExecuteUri())
                    .timeout(Duration.ofSeconds(normalizeRequestTimeoutSeconds(properties)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            applyContextHeaders(builder, context);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return parseResponse(response);
        } catch (StudioException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "studio-flink call failed: " + exception.getMessage(), exception);
        }
    }

    private URI resolveExecuteUri() {
        StudioPlatformProperties.FlinkClientProperties client = properties.getFlink().getClient();
        String baseUrl = client == null || !StringUtils.hasText(client.getBaseUrl())
                ? "http://127.0.0.1:18084"
                : client.getBaseUrl().trim();
        String path = client == null || client.getPath() == null ? "" : client.getPath().trim();
        String endpoint = trimTrailingSlash(baseUrl) + normalizeContextPath(path) + "/api/flink/sql/execute";
        return URI.create(endpoint);
    }

    private FlinkQuestionResultView parseResponse(HttpResponse<String> response) throws Exception {
        String body = response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "studio-flink call failed: HTTP " + response.statusCode() + remoteMessage(body));
        }
        JsonNode root = objectMapper.readTree(body);
        if (!root.path("success").asBoolean(false)) {
            String code = root.path("code").asText(StudioErrorCode.BAD_REQUEST);
            String message = root.path("message").asText("Flink SQL execution failed");
            throw new StudioException(code, "studio-flink: " + message);
        }
        JsonNode data = root.path("data");
        if (data == null || data.isMissingNode() || data.isNull()) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "studio-flink returned an empty result");
        }
        return objectMapper.convertValue(data, FlinkQuestionResultView.class);
    }

    private void applyContextHeaders(HttpRequest.Builder builder, DataDevelopmentExecutionContext context) {
        String username = resolveTokenUsername(context);
        if (StringUtils.hasText(username)) {
            builder.header(AUTHORIZATION_HEADER, "Bearer " + jwtTokenService.createToken(username));
        }
        if (StringUtils.hasText(context.getTenantId())) {
            builder.header(StudioConstants.REQUEST_TENANT_HEADER, context.getTenantId());
        }
        Object projectId = context.getRuntimeContext().get("projectId");
        if (projectId != null) {
            builder.header(StudioConstants.REQUEST_PROJECT_HEADER, String.valueOf(projectId));
        }
    }

    private String resolveTokenUsername(DataDevelopmentExecutionContext context) {
        Long triggeredByUserId = resolveLong(context.getRuntimeContext().get("triggeredByUserId"));
        if (triggeredByUserId != null) {
            StudioUserEntity user = studioUserMapper.selectById(triggeredByUserId);
            if (user != null && StringUtils.hasText(user.getUsername())) {
                return user.getUsername();
            }
        }
        if (StringUtils.hasText(context.getUsername())) {
            StudioUserEntity user = studioUserMapper.selectOne(new LambdaQueryWrapper<StudioUserEntity>()
                    .eq(StudioUserEntity::getUsername, context.getUsername())
                    .last("limit 1"));
            if (user != null) {
                return context.getUsername();
            }
        }
        return StudioConstants.DEFAULT_ADMIN_USERNAME;
    }

    private SqlExecutionResultView toSqlResult(FlinkQuestionResultView flinkResult,
                                               DataDevelopmentExecutionContext context,
                                               long startedAt) {
        SqlStatementExecutionResultView statementResult = new SqlStatementExecutionResultView();
        statementResult.setStatementIndex(1);
        statementResult.setSql(flinkResult.getSql() == null ? context.getContent() : flinkResult.getSql());
        statementResult.setQuery(Boolean.TRUE);
        statementResult.setAffectedRows(0);
        statementResult.setColumns(safeColumns(flinkResult));
        statementResult.setRows(safeRows(flinkResult));
        statementResult.setExecutionMs(flinkResult.getExecutionMs());
        statementResult.setMessage(String.format("Query returned %d row(s)", safeRows(flinkResult).size()));
        statementResult.getSummary().putAll(safeSummary(flinkResult));
        statementResult.getSummary().put("rowCount", safeRows(flinkResult).size());

        SqlExecutionResultView sqlResult = new SqlExecutionResultView();
        sqlResult.setQuery(Boolean.TRUE);
        sqlResult.setStatementCount(1);
        sqlResult.setAffectedRows(0);
        sqlResult.setExecutionMs(flinkResult.getExecutionMs() == null
                ? Long.valueOf(System.currentTimeMillis() - startedAt)
                : flinkResult.getExecutionMs());
        sqlResult.setMessage("模型 Flink SQL executed successfully");
        sqlResult.setColumns(safeColumns(flinkResult));
        sqlResult.setRows(safeRows(flinkResult));
        sqlResult.getSummary().putAll(safeSummary(flinkResult));
        sqlResult.getSummary().put("rowCount", safeRows(flinkResult).size());
        sqlResult.getSummary().put("flinkSql", statementResult.getSql());
        sqlResult.getResults().add(statementResult);
        return sqlResult;
    }

    private Map<String, Object> toResultJson(FlinkQuestionResultView flinkResult) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.putAll(safeSummary(flinkResult));
        result.put("sql", flinkResult.getSql());
        result.put("modelRefs", objectMapper.convertValue(
                flinkResult.getModelRefs() == null ? new ArrayList<Object>() : flinkResult.getModelRefs(), Object.class));
        result.put("warnings", new ArrayList<String>(safeWarnings(flinkResult)));
        return result;
    }

    private List<String> safeColumns(FlinkQuestionResultView result) {
        return result.getColumns() == null ? new ArrayList<String>() : new ArrayList<String>(result.getColumns());
    }

    private List<Map<String, Object>> safeRows(FlinkQuestionResultView result) {
        return result.getRows() == null
                ? new ArrayList<Map<String, Object>>()
                : new ArrayList<Map<String, Object>>(result.getRows());
    }

    private List<String> safeWarnings(FlinkQuestionResultView result) {
        return result.getWarnings() == null ? new ArrayList<String>() : new ArrayList<String>(result.getWarnings());
    }

    private Map<String, Object> safeSummary(FlinkQuestionResultView result) {
        return result.getSummary() == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(result.getSummary());
    }

    private List<Long> resolveModelIds(DataDevelopmentExecutionContext context) {
        Object candidate = context.getExecutionConfig().get("modelIds");
        if (candidate == null) {
            candidate = context.getArguments().get("modelIds");
        }
        return toLongList(candidate);
    }

    private Integer resolveInteger(DataDevelopmentExecutionContext context, String key) {
        Object candidate = context.getExecutionConfig().get(key);
        if (candidate == null) {
            candidate = context.getArguments().get(key);
        }
        Long value = resolveLong(candidate);
        return value == null ? null : value.intValue();
    }

    private List<Long> toLongList(Object value) {
        List<Long> result = new ArrayList<Long>();
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                Long resolved = resolveLong(item);
                if (resolved != null) {
                    result.add(resolved);
                }
            }
            return result;
        }
        if (value instanceof String) {
            String[] parts = ((String) value).split(",");
            for (String part : parts) {
                Long resolved = resolveLong(part);
                if (resolved != null) {
                    result.add(resolved);
                }
            }
            return result;
        }
        Long single = resolveLong(value);
        if (single != null) {
            result.add(single);
        }
        return result;
    }

    private Long resolveLong(Object value) {
        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        if (value instanceof String && StringUtils.hasText((String) value)) {
            try {
                return Long.valueOf(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String remoteMessage(String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(body, MAP_TYPE);
            Object message = payload.get("message");
            return message == null ? "" : ": " + message;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String normalizeContextPath(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String path = value.trim();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path;
    }

    private long normalizeConnectTimeoutSeconds(StudioPlatformProperties properties) {
        StudioPlatformProperties.FlinkClientProperties client = properties.getFlink().getClient();
        Integer value = client == null ? null : client.getConnectTimeoutSeconds();
        return Math.max(1L, value == null ? 10L : value.longValue());
    }

    private long normalizeRequestTimeoutSeconds(StudioPlatformProperties properties) {
        StudioPlatformProperties.FlinkClientProperties client = properties.getFlink().getClient();
        Integer value = client == null ? null : client.getRequestTimeoutSeconds();
        return Math.max(1L, value == null ? 120L : value.longValue());
    }
}
