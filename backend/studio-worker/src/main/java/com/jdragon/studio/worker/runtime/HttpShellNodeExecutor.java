package com.jdragon.studio.worker.runtime;

import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.core.spi.NodeExecutor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class HttpShellNodeExecutor implements NodeExecutor {

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public boolean supports(WorkflowNodeDefinition definition) {
        return definition.getNodeType() == NodeType.HTTP || definition.getNodeType() == NodeType.SHELL;
    }

    @Override
    public Map<String, Object> execute(WorkflowNodeDefinition definition, Map<String, Object> runtimeContext) {
        if (definition.getNodeType() == NodeType.HTTP) {
            return executeHttp(definition.getConfig());
        }
        return executeShell(definition.getConfig());
    }

    private Map<String, Object> executeHttp(Map<String, Object> config) {
        String url = String.valueOf(config.get("url"));
        String method = String.valueOf(config.getOrDefault("method", "GET"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        HttpHeaders headers = new HttpHeaders();
        Object headerCandidate = config.get("headers");
        if (headerCandidate instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) headerCandidate;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                headers.add(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        String response = restTemplate.exchange(url, HttpMethod.resolve(method), new HttpEntity<Object>(config.get("body"), headers), String.class).getBody();
        result.put("status", "SUCCESS");
        result.put("response", response);
        return result;
    }

    private Map<String, Object> executeShell(Map<String, Object> config) {
        String command = String.valueOf(config.get("command"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        try {
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
            process.waitFor();
            result.put("status", "SUCCESS");
            result.put("exitCode", process.exitValue());
            result.put("output", output.toString());
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to execute shell command", e);
        }
    }
}

