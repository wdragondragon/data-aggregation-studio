package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class AssistantScriptSkillExecutionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };

    private final AssistantBuiltInSkillRegistry builtInSkillRegistry;
    private final StudioPlatformProperties platformProperties;
    private final ObjectMapper objectMapper;

    public AssistantScriptSkillExecutionService(AssistantBuiltInSkillRegistry builtInSkillRegistry,
                                                StudioPlatformProperties platformProperties,
                                                ObjectMapper objectMapper) {
        this.builtInSkillRegistry = builtInSkillRegistry;
        this.platformProperties = platformProperties;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> execute(Map<String, Object> params) {
        String entrypointId = stringValue(params == null ? null : params.get("entrypointId"));
        if (!StringUtils.hasText(entrypointId)) {
            throw new IllegalArgumentException("assistant script entrypointId is required");
        }
        Map<String, Object> entrypoint = requireEntrypoint(entrypointId);
        Map<String, Object> input = inputPayload(params);
        assertInputSchema(entrypoint, input);
        String language = stringValue(entrypoint.get("language"));
        if (!"python".equalsIgnoreCase(language)) {
            throw new IllegalArgumentException("assistant script executor only supports python entrypoints");
        }
        String location = stringValue(entrypoint.get("entrypoint"));
        if (!location.startsWith("classpath:")) {
            throw new IllegalArgumentException("assistant script entrypoint must be a classpath resource");
        }
        return executePythonEntrypoint(entrypoint, location.substring("classpath:".length()), input);
    }

    private Map<String, Object> executePythonEntrypoint(Map<String, Object> entrypoint,
                                                       String resourcePath,
                                                       Map<String, Object> input) {
        String executable = platformProperties == null || platformProperties.getPython() == null
                ? null
                : platformProperties.getPython().getExecutable();
        if (!StringUtils.hasText(executable)) {
            throw new IllegalStateException("Python executable is not configured. Please set studio.python.executable before running assistant script skills.");
        }
        Path workingDirectory = null;
        try {
            workingDirectory = createWorkingDirectory();
            Path scriptPath = workingDirectory.resolve("assistant-script.py");
            Files.write(scriptPath, readResource(resourcePath).getBytes(StandardCharsets.UTF_8));
            PythonExecution execution = runPython(executable, scriptPath, input, workingDirectory);
            if (execution.timedOut) {
                throw new IllegalStateException("Assistant script execution timed out");
            }
            if (execution.exitCode != 0) {
                throw new IllegalStateException("Assistant script exited with code " + execution.exitCode
                        + (StringUtils.hasText(execution.stderr) ? ": " + execution.stderr.trim() : ""));
            }
            Map<String, Object> output = objectMapper.readValue(execution.stdout, MAP_TYPE);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("schema", stringValue(output.get("schema")));
            result.put("success", output.get("success"));
            result.put("entrypointId", entrypoint.get("id"));
            result.put("language", entrypoint.get("language"));
            result.put("data", output.get("data"));
            if (output.containsKey("message")) {
                result.put("message", output.get("message"));
            }
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("Assistant script execution failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Assistant script execution was interrupted", ex);
        } finally {
            deleteRecursively(workingDirectory);
        }
    }

    private PythonExecution runPython(String executable,
                                      Path scriptPath,
                                      Map<String, Object> input,
                                      Path workingDirectory) throws IOException, InterruptedException {
        List<String> command = new ArrayList<String>();
        command.add(executable.trim());
        if (platformProperties.getPython() != null && platformProperties.getPython().getExecutableArgs() != null) {
            for (String argument : platformProperties.getPython().getExecutableArgs()) {
                if (StringUtils.hasText(argument)) {
                    command.add(argument.trim());
                }
            }
        }
        command.add(scriptPath.toAbsolutePath().normalize().toString());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory.toFile());
        processBuilder.environment().put("PYTHONIOENCODING", "utf-8");
        processBuilder.environment().put("PYTHONUNBUFFERED", "1");
        Process process = processBuilder.start();
        ProcessOutputCollector stdout = new ProcessOutputCollector(process.getInputStream());
        ProcessOutputCollector stderr = new ProcessOutputCollector(process.getErrorStream());
        stdout.start();
        stderr.start();
        try (OutputStream outputStream = process.getOutputStream()) {
            objectMapper.writeValue(outputStream, input);
        }
        long timeoutSeconds = platformProperties.getPython() == null || platformProperties.getPython().getExecutionTimeoutSeconds() == null
                ? 120L
                : Math.max(1L, platformProperties.getPython().getExecutionTimeoutSeconds());
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            stdout.joinQuietly();
            stderr.joinQuietly();
            return new PythonExecution(true, -1, stdout.snapshot(), stderr.snapshot());
        }
        stdout.joinQuietly();
        stderr.joinQuietly();
        return new PythonExecution(false, process.exitValue(), stdout.snapshot(), stderr.snapshot());
    }

    private Map<String, Object> requireEntrypoint(String entrypointId) {
        for (Map<String, Object> skill : builtInSkillRegistry.allPortableSkills()) {
            if (!"assistant.script.skill".equals(stringValue(skill.get("kind")))) {
                continue;
            }
            Object entrypoints = skill.get("scriptEntrypoints");
            if (!(entrypoints instanceof List)) {
                continue;
            }
            for (Object item : (List<?>) entrypoints) {
                if (!(item instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> entrypoint = (Map<String, Object>) item;
                if (entrypointId.equals(stringValue(entrypoint.get("id")))) {
                    return entrypoint;
                }
            }
        }
        throw new IllegalArgumentException("assistant script entrypoint is not registered: " + entrypointId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inputPayload(Map<String, Object> params) {
        Object input = params == null ? null : params.get("input");
        if (input instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) input);
        }
        throw new IllegalArgumentException("assistant script input is required");
    }

    private void assertInputSchema(Map<String, Object> entrypoint, Map<String, Object> input) {
        Object schema = entrypoint.get("inputSchema");
        if (!(schema instanceof Map)) {
            return;
        }
        Object required = ((Map<?, ?>) schema).get("required");
        if (!(required instanceof List)) {
            return;
        }
        for (Object key : (List<?>) required) {
            String name = stringValue(key);
            if (StringUtils.hasText(name) && (input == null || !input.containsKey(name))) {
                throw new IllegalArgumentException("assistant script input is missing required field: " + name);
            }
        }
    }

    private Path createWorkingDirectory() throws IOException {
        String configuredTempDir = platformProperties == null || platformProperties.getPython() == null
                ? null
                : platformProperties.getPython().getTempDir();
        if (!StringUtils.hasText(configuredTempDir)) {
            return Files.createTempDirectory("studio-assistant-script-");
        }
        Path root = Paths.get(configuredTempDir.trim()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "studio-assistant-script-");
    }

    private String readResource(String resourcePath) throws IOException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("assistant script resource not found: " + resourcePath);
            }
            byte[] buffer = inputStream.readAllBytes();
            return new String(buffer, StandardCharsets.UTF_8);
        }
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(item -> {
                        try {
                            Files.deleteIfExists(item);
                        } catch (IOException ignored) {
                            // Temporary script workspaces should not break assistant responses during cleanup.
                        }
                    });
        } catch (IOException ignored) {
            // Temporary script workspaces should not break assistant responses during cleanup.
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static final class PythonExecution {
        private final boolean timedOut;
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private PythonExecution(boolean timedOut, int exitCode, String stdout, String stderr) {
            this.timedOut = timedOut;
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }
    }

    private static final class ProcessOutputCollector extends Thread {
        private final InputStream inputStream;
        private final StringBuilder output = new StringBuilder();

        private ProcessOutputCollector(InputStream inputStream) {
            this.inputStream = inputStream;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                byte[] buffer = new byte[1024];
                int length = inputStream.read(buffer);
                while (length >= 0) {
                    output.append(new String(buffer, 0, length, StandardCharsets.UTF_8));
                    length = inputStream.read(buffer);
                }
            } catch (IOException ignored) {
                // Process output is best-effort diagnostic context.
            }
        }

        private void joinQuietly() {
            try {
                join(1000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        private String snapshot() {
            return output.toString();
        }
    }
}
