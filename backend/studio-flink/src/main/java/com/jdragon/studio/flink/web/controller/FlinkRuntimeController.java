package com.jdragon.studio.flink.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.flink.connector.AggregationFlinkRuntimeRegistry;
import com.jdragon.studio.flink.connector.AggregationFlinkTableRuntimePayload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Flink Runtime", description = "Internal DataAggregation Flink runtime callback APIs")
@RestController
@ConditionalOnClass(name = "com.jdragon.studio.worker.bootstrap.StudioWorkerApplication")
@RequestMapping("/api/flink/runtime")
public class FlinkRuntimeController {

    @Operation(summary = "Resolve short-lived DataAggregation runtime for remote Flink connector")
    @PostMapping("/resolve")
    public Result<AggregationFlinkTableRuntimePayload> resolve(
            @RequestHeader(AggregationFlinkRuntimeRegistry.CAPABILITY_TOKEN_HEADER) String token) {
        return Result.success(AggregationFlinkRuntimeRegistry.resolvePayload(token));
    }

    @Operation(summary = "Update pushdown audit for remote Flink connector")
    @PostMapping("/audit")
    public Result<Boolean> audit(
            @RequestHeader(AggregationFlinkRuntimeRegistry.CAPABILITY_TOKEN_HEADER) String token,
            @RequestBody RuntimeAuditRequest request) {
        AggregationFlinkRuntimeRegistry.updateAudit(token, request.getRuntime());
        return Result.success(Boolean.TRUE);
    }

    public static class RuntimeAuditRequest {
        private AggregationFlinkTableRuntimePayload runtime;

        public AggregationFlinkTableRuntimePayload getRuntime() {
            return runtime;
        }

        public void setRuntime(AggregationFlinkTableRuntimePayload runtime) {
            this.runtime = runtime;
        }
    }
}
