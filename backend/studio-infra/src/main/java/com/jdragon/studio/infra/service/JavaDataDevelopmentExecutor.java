package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;
import com.jdragon.studio.infra.script.java.BufferingJavaDataScriptLogger;
import com.jdragon.studio.infra.script.java.DefaultJavaDataScriptContext;
import com.jdragon.studio.infra.script.java.DefaultJavaDataScriptServices;
import com.jdragon.studio.infra.script.java.JavaDataScript;
import com.jdragon.studio.infra.script.java.JavaDataScriptResult;
import com.jdragon.studio.infra.service.script.DataDevelopmentExecutionContext;
import com.jdragon.studio.infra.service.script.DataDevelopmentScriptExecutor;
import org.codehaus.janino.SimpleCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavaDataDevelopmentExecutor implements DataDevelopmentScriptExecutor {

    private static final Logger log = LoggerFactory.getLogger(JavaDataDevelopmentExecutor.class);
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("package\\s+([a-zA-Z_$][\\w$.]*)\\s*;");
    private static final Pattern CLASS_PATTERN = Pattern.compile("public\\s+class\\s+([a-zA-Z_$][\\w$]*)");
    private static final Map<String, Class<? extends JavaDataScript>> COMPILED_CACHE = new ConcurrentHashMap<String, Class<? extends JavaDataScript>>();

    private final DataSourceService dataSourceService;
    private final DataModelService dataModelService;
    private final DataDevelopmentSqlExecutor sqlExecutor;
    private final ScriptEnvironmentRuntimeService environmentRuntimeService;
    private final DatasourceClusterBindingService datasourceClusterBindingService;

    public JavaDataDevelopmentExecutor(DataSourceService dataSourceService,
                                       DataModelService dataModelService,
                                       DataDevelopmentSqlExecutor sqlExecutor,
                                       ScriptEnvironmentRuntimeService environmentRuntimeService,
                                       DatasourceClusterBindingService datasourceClusterBindingService) {
        this.dataSourceService = dataSourceService;
        this.dataModelService = dataModelService;
        this.sqlExecutor = sqlExecutor;
        this.environmentRuntimeService = environmentRuntimeService;
        this.datasourceClusterBindingService = datasourceClusterBindingService;
    }

    @Override
    public ScriptType getScriptType() {
        return ScriptType.JAVA;
    }

    @Override
    public DataScriptExecutionResultView execute(DataDevelopmentExecutionContext context) {
        long startedAt = System.currentTimeMillis();
        BufferingJavaDataScriptLogger logger = new BufferingJavaDataScriptLogger(log);
        DataScriptExecutionResultView result = new DataScriptExecutionResultView();
        result.setScriptType(ScriptType.JAVA);
        try (ScriptEnvironmentRuntimeService.RuntimeLease lease = environmentRuntimeService.resolveRuntime(context.getEnvironmentId())) {
            Class<? extends JavaDataScript> scriptClass = resolveScriptClass(context, lease.getRuntime());
            JavaDataScript script = scriptClass.getDeclaredConstructor().newInstance();
            DefaultJavaDataScriptContext scriptContext = new DefaultJavaDataScriptContext(
                    context.getTenantId(),
                    context.getUsername(),
                    context.getArguments(),
                    context.getRuntimeContext(),
                    logger,
                    new DefaultJavaDataScriptServices(
                            dataSourceService,
                            dataModelService,
                            sqlExecutor,
                            datasourceClusterBindingService,
                            context.getRuntimeProjectId(),
                            context.getRuntimeClusterId())
            );
            JavaDataScriptResult executionResult = script.execute(scriptContext);
            long endedAt = System.currentTimeMillis();
            result.setSuccess(executionResult == null || executionResult.isSuccess());
            result.setStatus(executionResult == null || executionResult.getStatus() == null
                    ? (Boolean.TRUE.equals(result.getSuccess()) ? "SUCCESS" : "FAILED")
                    : executionResult.getStatus());
            result.setMessage(executionResult == null ? "Java script finished" : executionResult.getMessage());
            result.setExecutionMs(endedAt - startedAt);
            result.setLogs(logger.snapshot());
            result.setResultJson(executionResult == null
                    ? new LinkedHashMap<String, Object>()
                    : executionResult.getResultJson());
            if (result.getMessage() == null || result.getMessage().trim().isEmpty()) {
                result.setMessage(Boolean.TRUE.equals(result.getSuccess()) ? "Java script executed successfully" : "Java script execution failed");
            }
            return result;
        } catch (Exception ex) {
            long endedAt = System.currentTimeMillis();
            logger.error("Java script execution failed: " + ex.getMessage());
            result.setSuccess(Boolean.FALSE);
            result.setStatus("FAILED");
            result.setMessage(ex.getMessage());
            result.setExecutionMs(endedAt - startedAt);
            result.setLogs(logger.snapshot());
            LinkedHashMap<String, Object> error = new LinkedHashMap<String, Object>();
            error.put("exceptionType", ex.getClass().getName());
            error.put("stackTrace", stackTraceOf(ex));
            result.setResultJson(error);
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private Class<? extends JavaDataScript> resolveScriptClass(DataDevelopmentExecutionContext context,
                                                               ScriptEnvironmentRuntimeService.RuntimeClassLoaderHolder runtime) throws Exception {
        String source = context.getContent() == null ? "" : context.getContent().trim();
        if (source.isEmpty()) {
            throw new IllegalArgumentException("Java script content is empty");
        }
        String cacheKey = buildCacheKey(context.getScriptId(), runtime.getEnvironmentId(), runtime.getEnvironmentVersion(), source);
        Class<? extends JavaDataScript> cached = COMPILED_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String fqcn = resolveClassName(source);
        SimpleCompiler compiler = new SimpleCompiler();
        compiler.setParentClassLoader(runtime.getClassLoader());
        compiler.cook(source);
        Class<?> loadedClass = compiler.getClassLoader().loadClass(fqcn);
        if (!JavaDataScript.class.isAssignableFrom(loadedClass)) {
            throw new IllegalArgumentException("Java script must implement com.jdragon.studio.infra.script.java.JavaDataScript");
        }
        Class<? extends JavaDataScript> compiled = (Class<? extends JavaDataScript>) loadedClass;
        COMPILED_CACHE.put(cacheKey, compiled);
        return compiled;
    }

    private String resolveClassName(String source) {
        Matcher classMatcher = CLASS_PATTERN.matcher(source);
        if (!classMatcher.find()) {
            throw new IllegalArgumentException("Java script must declare a public class");
        }
        String className = classMatcher.group(1);
        Matcher packageMatcher = PACKAGE_PATTERN.matcher(source);
        if (packageMatcher.find()) {
            return packageMatcher.group(1) + "." + className;
        }
        return className;
    }

    private String buildCacheKey(Long scriptId, Long environmentId, Long environmentVersion, String source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(source.getBytes("UTF-8"));
        StringBuilder builder = new StringBuilder();
        builder.append(scriptId == null ? "adhoc" : String.valueOf(scriptId)).append(':');
        builder.append(environmentId == null ? "default" : String.valueOf(environmentId)).append(':');
        builder.append(environmentVersion == null ? "0" : String.valueOf(environmentVersion)).append(':');
        for (byte item : hash) {
            builder.append(String.format(Locale.ENGLISH, "%02x", item));
        }
        return builder.toString();
    }

    public static void clearCompiledCache() {
        COMPILED_CACHE.clear();
    }

    public static void clearCompiledCache(Long environmentId, Long environmentVersion) {
        if (environmentId == null && environmentVersion == null) {
            clearCompiledCache();
            return;
        }
        for (String key : new ArrayList<String>(COMPILED_CACHE.keySet())) {
            if (matchesEnvironment(key, environmentId, environmentVersion)) {
                COMPILED_CACHE.remove(key);
            }
        }
    }

    private static boolean matchesEnvironment(String key, Long environmentId, Long environmentVersion) {
        String[] parts = key == null ? new String[0] : key.split(":", 4);
        if (parts.length < 4) {
            return false;
        }
        if (environmentId != null && !String.valueOf(environmentId).equals(parts[1])) {
            return false;
        }
        return environmentVersion == null || String.valueOf(environmentVersion).equals(parts[2]);
    }

    private String stackTraceOf(Throwable throwable) {
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        throwable.printStackTrace(printWriter);
        printWriter.flush();
        return writer.toString();
    }
}
