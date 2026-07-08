package com.jdragon.studio.flink.web.controller;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.flink.connector.AggregationFlinkRuntimeRegistry;
import com.jdragon.studio.flink.connector.AggregationFlinkTableRuntimePayload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Flink Runtime", description = "Internal DataAggregation Flink runtime callback APIs")
@RestController
@RequestMapping("/api/flink/runtime")
public class FlinkRuntimeController {

    @Operation(summary = "Resolve short-lived DataAggregation runtime for remote Flink connector")
    @PostMapping("/resolve")
    public Result<AggregationFlinkTableRuntimePayload> resolve(@RequestBody RuntimeResolveRequest request) {
        String token = requiredToken(request == null ? null : request.getToken());
        return Result.success(AggregationFlinkRuntimeRegistry.resolvePayload(token));
    }

    @Operation(summary = "Update pushdown audit for remote Flink connector")
    @PostMapping("/audit")
    public Result<Boolean> audit(@RequestBody RuntimeAuditRequest request) {
        String token = requiredToken(request == null ? null : request.getToken());
        AggregationFlinkRuntimeRegistry.updateAudit(token, request.getRuntime());
        return Result.success(Boolean.TRUE);
    }

    private String requiredToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "runtime token is required");
        }
        return token.trim();
    }

    public static class RuntimeResolveRequest {
        private String token;

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    public static class RuntimeAuditRequest {
        private String token;
        private AggregationFlinkTableRuntimePayload runtime;

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public AggregationFlinkTableRuntimePayload getRuntime() {
            return runtime;
        }

        public void setRuntime(AggregationFlinkTableRuntimePayload runtime) {
            this.runtime = runtime;
        }
    }
}
