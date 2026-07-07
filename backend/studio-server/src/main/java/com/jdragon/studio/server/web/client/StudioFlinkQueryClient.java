package com.jdragon.studio.server.web.client;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.FlinkQuestionResultView;
import com.jdragon.studio.dto.model.request.FlinkQuestionAskRequest;
import com.jdragon.studio.dto.model.request.FlinkSqlExecuteRequest;
import com.jdragon.studio.server.web.config.StudioFlinkFeignConfig;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(
        name = "${studio.flink.client.service-name:studio-flink}",
        url = "${studio.flink.client.base-url:http://127.0.0.1:18084}",
        path = "${studio.flink.client.path:}/api/flink",
        contextId = "studioFlinkQueryClient",
        configuration = StudioFlinkFeignConfig.class
)
public interface StudioFlinkQueryClient {

    @PostMapping("/sql/execute")
    Result<FlinkQuestionResultView> executeSql(@Valid @RequestBody FlinkSqlExecuteRequest request);

    @PostMapping("/question/ask")
    Result<FlinkQuestionResultView> ask(@Valid @RequestBody FlinkQuestionAskRequest request);
}
