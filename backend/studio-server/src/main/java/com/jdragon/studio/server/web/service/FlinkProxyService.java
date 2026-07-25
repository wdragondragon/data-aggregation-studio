package com.jdragon.studio.server.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.FlinkQuestionPlanView;
import com.jdragon.studio.dto.model.FlinkQuestionResultView;
import com.jdragon.studio.dto.model.request.FlinkQuestionAskRequest;
import com.jdragon.studio.dto.model.request.FlinkSqlExecuteRequest;
import com.jdragon.studio.server.web.client.StudioFlinkQueryClient;
import feign.FeignException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;

@Service
public class FlinkProxyService {

    private final StudioFlinkQueryClient flinkQueryClient;
    private final RuntimeFlinkExecutionRouter executionRouter;
    private final ObjectMapper objectMapper;

    public FlinkProxyService(StudioFlinkQueryClient flinkQueryClient,
                             RuntimeFlinkExecutionRouter executionRouter,
                             ObjectMapper objectMapper) {
        this.flinkQueryClient = flinkQueryClient;
        this.executionRouter = executionRouter;
        this.objectMapper = objectMapper;
    }

    public Result<FlinkQuestionResultView> executeSql(FlinkSqlExecuteRequest request) {
        return Result.success(executionRouter.execute(request));
    }

    public Result<FlinkQuestionResultView> ask(FlinkQuestionAskRequest request) {
        FlinkQuestionPlanView plan = createQuestionPlan(request);
        FlinkSqlExecuteRequest executeRequest = new FlinkSqlExecuteRequest();
        executeRequest.setRuntimeClusterId(request.getRuntimeClusterId());
        executeRequest.setSql(plan.getSql());
        executeRequest.setModelIds(plan.getModelIds() == null
                ? new ArrayList<Long>() : new ArrayList<Long>(plan.getModelIds()));
        executeRequest.setMaxRows(plan.getMaxRows());
        executeRequest.setScanMaxRows(plan.getScanMaxRows());

        FlinkQuestionResultView result = executionRouter.execute(executeRequest);
        result.setQuestion(request.getQuestion());
        if (plan.getWarnings() != null) {
            if (result.getWarnings() == null) {
                result.setWarnings(new ArrayList<String>());
            }
            result.getWarnings().addAll(plan.getWarnings());
        }
        return Result.success(result);
    }

    private FlinkQuestionPlanView createQuestionPlan(FlinkQuestionAskRequest request) {
        try {
            Result<FlinkQuestionPlanView> result = flinkQueryClient.plan(request);
            if (result == null || !result.isSuccess()) {
                String code = result == null || !StringUtils.hasText(result.getCode())
                        ? StudioErrorCode.INTERNAL_SERVER_ERROR : result.getCode();
                String message = result == null || !StringUtils.hasText(result.getMessage())
                        ? "studio-flink returned an empty question plan" : result.getMessage();
                throw new StudioException(code, "studio-flink: " + message);
            }
            if (result.getData() == null || !StringUtils.hasText(result.getData().getSql())) {
                throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                        "studio-flink returned an empty question plan");
            }
            return result.getData();
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
                // Fall through to a transport-level message when the remote body is not Result JSON.
            }
        }
        return new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                "studio-flink call failed: HTTP " + exception.status(), exception);
    }
}
