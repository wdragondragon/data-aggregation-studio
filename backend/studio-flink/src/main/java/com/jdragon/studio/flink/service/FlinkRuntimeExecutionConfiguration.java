package com.jdragon.studio.flink.service;

import com.jdragon.studio.flink.execution.EmbeddedFlinkExecutionClient;
import com.jdragon.studio.flink.execution.FlinkExecutionClientRouter;
import com.jdragon.studio.flink.execution.GatewayFlinkExecutionClient;
import com.jdragon.studio.flink.web.controller.FlinkRuntimeController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Exposes the Flink execution runtime only when studio-flink is embedded in a
 * Worker. The standalone studio-flink application remains the SQL planning
 * control-plane service.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "com.jdragon.studio.worker.bootstrap.StudioWorkerApplication")
@Import({
        AggregationFlinkRuntimeBuilder.class,
        FlinkSqlGuard.class,
        EmbeddedFlinkExecutionClient.class,
        GatewayFlinkExecutionClient.class,
        FlinkExecutionClientRouter.class,
        FlinkSqlExecutionService.class,
        FlinkRuntimeController.class
})
public class FlinkRuntimeExecutionConfiguration {
}
