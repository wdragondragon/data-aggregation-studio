package com.jdragon.studio.server.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.FlinkQuestionResultView;
import com.jdragon.studio.dto.model.request.FlinkQuestionAskRequest;
import com.jdragon.studio.dto.model.request.FlinkSqlExecuteRequest;
import com.jdragon.studio.server.web.client.StudioFlinkQueryClient;
import feign.FeignException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class FlinkProxyService {

    private final StudioFlinkQueryClient flinkQueryClient;
    private final ObjectMapper objectMapper;

    public FlinkProxyService(StudioFlinkQueryClient flinkQueryClient, ObjectMapper objectMapper) {
        this.flinkQueryClient = flinkQueryClient;
        this.objectMapper = objectMapper;
    }

    public Result<FlinkQuestionResultView> executeSql(FlinkSqlExecuteRequest request) {
        try {
            return flinkQueryClient.executeSql(request);
        } catch (FeignException exception) {
            throw toStudioException(exception);
        }
    }

    public Result<FlinkQuestionResultView> ask(FlinkQuestionAskRequest request) {
        try {
            return flinkQueryClient.ask(request);
        } catch (FeignException exception) {
            throw toStudioException(exception);
        }
    }

    private StudioException toStudioException(FeignException exception) {
        String body = exception.contentUTF8();
        if (StringUtils.hasText(body)) {
            try {
                JsonNode root = objectMapper.readTree(body);
                String code = root.path("code").asText(StudioErrorCode.INTERNAL_SERVER_ERROR);
                String message = root.path("message").asText();
                if (StringUtils.hasText(message)) {
                    return new StudioException(code, "studio-flink: " + message, exception);
                }
            } catch (Exception ignored) {
                // Fall through to a transport-level message when the remote body is not a Result JSON.
            }
        }
        return new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                "studio-flink call failed: HTTP " + exception.status(), exception);
    }
}
