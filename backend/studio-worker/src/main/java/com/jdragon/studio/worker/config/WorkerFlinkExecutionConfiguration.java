package com.jdragon.studio.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.flink.service.FlinkRuntimeExecutionConfiguration;
import com.jdragon.studio.flink.service.FlinkSqlExecutionService;
import com.jdragon.studio.infra.service.FlinkQuestionSqlDataDevelopmentExecutor;
import com.jdragon.studio.infra.service.script.DataDevelopmentScriptExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Registers Flink SQL execution and the data-development adapter only in Worker. */
@Configuration(proxyBeanMethods = false)
@Import(FlinkRuntimeExecutionConfiguration.class)
public class WorkerFlinkExecutionConfiguration {

    @Bean
    public DataDevelopmentScriptExecutor flinkQuestionSqlDataDevelopmentExecutor(
            ObjectMapper objectMapper,
            FlinkSqlExecutionService executionService) {
        return new FlinkQuestionSqlDataDevelopmentExecutor(objectMapper, executionService::execute);
    }
}
