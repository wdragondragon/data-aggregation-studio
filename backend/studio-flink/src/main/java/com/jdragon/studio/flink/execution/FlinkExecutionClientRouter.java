package com.jdragon.studio.flink.execution;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@ConditionalOnClass(name = "com.jdragon.studio.worker.bootstrap.StudioWorkerApplication")
public class FlinkExecutionClientRouter {
    private final Map<String, FlinkExecutionClient> clients = new LinkedHashMap<String, FlinkExecutionClient>();

    public FlinkExecutionClientRouter(List<FlinkExecutionClient> clients) {
        if (clients != null) {
            for (FlinkExecutionClient client : clients) {
                this.clients.put(normalize(client.executionMode()), client);
            }
        }
    }

    public FlinkExecutionClient select(String executionMode) {
        String mode = normalize(executionMode);
        FlinkExecutionClient client = clients.get(mode);
        if (client == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Unsupported Flink execution mode: " + executionMode);
        }
        return client;
    }

    private String normalize(String executionMode) {
        if (executionMode == null || executionMode.trim().isEmpty()) {
            return "embedded";
        }
        return executionMode.trim().toLowerCase(Locale.ENGLISH);
    }
}
