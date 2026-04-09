package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.script.java.DefaultJavaDataScriptServices;
import com.jdragon.studio.infra.script.python.PythonDataScriptResult;
import com.jdragon.studio.infra.script.python.PythonExecutionServiceBridge;
import com.jdragon.studio.infra.script.python.PythonScriptContextPayload;
import com.jdragon.studio.infra.service.script.DataDevelopmentExecutionContext;
import com.jdragon.studio.infra.service.script.DataDevelopmentScriptExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.DirectoryStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class PythonDataDevelopmentExecutor implements DataDevelopmentScriptExecutor {

    private static final Logger log = LoggerFactory.getLogger(PythonDataDevelopmentExecutor.class);
    private static final DateTimeFormatter LOG_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StudioPlatformProperties platformProperties;
    private final ObjectMapper objectMapper;
    private final DataSourceService dataSourceService;
    private final DataModelService dataModelService;
    private final DataDevelopmentSqlExecutor sqlExecutor;

    public PythonDataDevelopmentExecutor(StudioPlatformProperties platformProperties,
                                         ObjectMapper objectMapper,
                                         DataSourceService dataSourceService,
                                         DataModelService dataModelService,
                                         DataDevelopmentSqlExecutor sqlExecutor) {
        this.platformProperties = platformProperties;
        this.objectMapper = objectMapper;
        this.dataSourceService = dataSourceService;
        this.dataModelService = dataModelService;
        this.sqlExecutor = sqlExecutor;
    }

    @Override
    public ScriptType getScriptType() {
        return ScriptType.PYTHON;
    }

    @Override
    public DataScriptExecutionResultView execute(DataDevelopmentExecutionContext context) {
        long startedAt = System.currentTimeMillis();
        DataScriptExecutionResultView result = new DataScriptExecutionResultView();
        result.setScriptType(ScriptType.PYTHON);

        String executable = platformProperties.getPython() == null ? null : platformProperties.getPython().getExecutable();
        if (executable == null || executable.trim().isEmpty()) {
            return failedResult("Python executable is not configured. Please set studio.python.executable on both server and worker.", "", startedAt, null);
        }

        Path workingDirectory = null;
        try {
            workingDirectory = createWorkingDirectory();
            Path userScript = workingDirectory.resolve("user_script.py");
            Path runtimeScript = workingDirectory.resolve("studio_runtime.py");
            Path runnerScript = workingDirectory.resolve("runner.py");
            Path contextFile = workingDirectory.resolve("context.json");
            Path resultFile = workingDirectory.resolve("result.json");

            writeUtf8(userScript, context.getContent());
            writeUtf8(runtimeScript, readResource("data-development/python/studio_runtime.py"));
            writeUtf8(runnerScript, readResource("data-development/python/runner.py"));

            DefaultJavaDataScriptServices scriptServices = new DefaultJavaDataScriptServices(
                    dataSourceService,
                    dataModelService,
                    sqlExecutor
            );
            try (PythonExecutionServiceBridge bridge = new PythonExecutionServiceBridge(objectMapper, scriptServices)) {
                objectMapper.writeValue(contextFile.toFile(), buildContextPayload(context, bridge));
                PythonProcessExecution execution = executePythonProcess(executable, runnerScript, userScript, contextFile, resultFile, workingDirectory);
                if (execution.timedOut) {
                    return failedResult("Python script execution timed out", execution.output, startedAt, null);
                }
                if (!Files.exists(resultFile)) {
                    String message = execution.exitCode == 0
                            ? "Python script did not produce a result payload"
                            : "Python script exited with code " + execution.exitCode;
                    return failedResult(message, execution.output, startedAt, null);
                }
                PythonDataScriptResult payload = objectMapper.readValue(resultFile.toFile(), PythonDataScriptResult.class);
                long endedAt = System.currentTimeMillis();
                result.setSuccess(payload.isSuccess());
                result.setStatus(payload.getStatus() == null
                        ? (payload.isSuccess() ? "SUCCESS" : "FAILED")
                        : payload.getStatus());
                result.setMessage(payload.getMessage() == null || payload.getMessage().trim().isEmpty()
                        ? (payload.isSuccess() ? "Python script executed successfully" : "Python script execution failed")
                        : payload.getMessage());
                result.setExecutionMs(endedAt - startedAt);
                result.setLogs(execution.output);
                result.setResultJson(payload.getResultJson());
                return result;
            }
        } catch (Exception ex) {
            log.warn("Python script execution failed", ex);
            return failedResult(ex.getMessage(), "", startedAt, ex);
        } finally {
            if (workingDirectory != null) {
                deleteRecursively(workingDirectory);
            }
        }
    }

    private PythonScriptContextPayload buildContextPayload(DataDevelopmentExecutionContext context,
                                                           PythonExecutionServiceBridge bridge) {
        PythonScriptContextPayload payload = new PythonScriptContextPayload();
        payload.setScriptId(context.getScriptId());
        payload.setScriptName(context.getScriptName());
        payload.setTenantId(context.getTenantId());
        payload.setUsername(context.getUsername());
        payload.setArguments(context.getArguments());
        payload.setRuntimeContext(context.getRuntimeContext());
        payload.setBridge(bridge.buildConnectionInfo());
        return payload;
    }

    private PythonProcessExecution executePythonProcess(String executable,
                                                        Path runnerScript,
                                                        Path userScript,
                                                        Path contextFile,
                                                        Path resultFile,
                                                        Path workingDirectory) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add(executable.trim());
        if (platformProperties.getPython() != null && platformProperties.getPython().getExecutableArgs() != null) {
            for (String argument : platformProperties.getPython().getExecutableArgs()) {
                if (argument != null && !argument.trim().isEmpty()) {
                    command.add(argument);
                }
            }
        }
        command.add(runnerScript.toAbsolutePath().normalize().toString());
        command.add(userScript.toAbsolutePath().normalize().toString());
        command.add(contextFile.toAbsolutePath().normalize().toString());
        command.add(resultFile.toAbsolutePath().normalize().toString());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory.toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().put("PYTHONIOENCODING", "utf-8");
        processBuilder.environment().put("PYTHONUNBUFFERED", "1");

        Process process = processBuilder.start();
        ProcessOutputCollector collector = new ProcessOutputCollector(process.getInputStream());
        collector.start();

        long timeoutSeconds = platformProperties.getPython() == null || platformProperties.getPython().getExecutionTimeoutSeconds() == null
                ? 120L
                : Math.max(1L, platformProperties.getPython().getExecutionTimeoutSeconds());
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            collector.joinQuietly();
            return new PythonProcessExecution(true, -1, collector.snapshot());
        }
        collector.joinQuietly();
        return new PythonProcessExecution(false, process.exitValue(), collector.snapshot());
    }

    private Path createWorkingDirectory() throws IOException {
        String configuredTempDir = platformProperties.getPython() == null ? null : platformProperties.getPython().getTempDir();
        if (configuredTempDir == null || configuredTempDir.trim().isEmpty()) {
            return Files.createTempDirectory("studio-python-script-");
        }
        Path root = Paths.get(configuredTempDir.trim()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "studio-python-script-");
    }

    private String readResource(String location) throws IOException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(location)) {
            if (inputStream == null) {
                throw new IOException("Python runtime resource not found: " + location);
            }
            InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[2048];
            int length = reader.read(buffer);
            while (length >= 0) {
                builder.append(buffer, 0, length);
                length = reader.read(buffer);
            }
            return builder.toString();
        }
    }

    private void writeUtf8(Path path, String content) throws IOException {
        Files.write(path, (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    private DataScriptExecutionResultView failedResult(String message,
                                                       String logs,
                                                       long startedAt,
                                                       Exception exception) {
        DataScriptExecutionResultView result = new DataScriptExecutionResultView();
        result.setScriptType(ScriptType.PYTHON);
        result.setSuccess(Boolean.FALSE);
        result.setStatus("FAILED");
        result.setMessage(message == null || message.trim().isEmpty() ? "Python script execution failed" : message);
        result.setExecutionMs(System.currentTimeMillis() - startedAt);
        result.setLogs(logs == null ? "" : logs);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        if (exception != null) {
            payload.put("exceptionType", exception.getClass().getName());
            payload.put("message", exception.getMessage());
        }
        result.setResultJson(payload);
        return result;
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                    for (Path child : stream) {
                        deleteRecursively(child);
                    }
                }
            }
            Files.deleteIfExists(path);
        } catch (Exception ex) {
            log.debug("Failed to clean python temp path {}", path, ex);
        }
    }

    private static final class PythonProcessExecution {
        private final boolean timedOut;
        private final int exitCode;
        private final String output;

        private PythonProcessExecution(boolean timedOut, int exitCode, String output) {
            this.timedOut = timedOut;
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static final class ProcessOutputCollector extends Thread {
        private final InputStream inputStream;
        private final StringBuilder buffer = new StringBuilder();

        private ProcessOutputCollector(InputStream inputStream) {
            super("studio-python-output");
            this.inputStream = inputStream;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                String line = reader.readLine();
                while (line != null) {
                    append(line);
                    line = reader.readLine();
                }
            } catch (Exception ignore) {
                append(formatLine("ERROR", "Failed to collect python process output"));
            }
        }

        private void append(String line) {
            synchronized (buffer) {
                if (buffer.length() > 0) {
                    buffer.append('\n');
                }
                buffer.append(line);
            }
        }

        private String snapshot() {
            synchronized (buffer) {
                return buffer.toString();
            }
        }

        private void joinQuietly() {
            try {
                join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        private String formatLine(String level, String message) {
            return LOG_FORMATTER.format(LocalDateTime.now()) + " [" + level + "] " + message;
        }
    }
}
