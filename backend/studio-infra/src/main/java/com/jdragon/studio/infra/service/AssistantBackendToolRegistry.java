package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.assistant.AssistantPlanRequest;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AssistantBackendToolRegistry {

    private final ApplicationContext applicationContext;
    private volatile Map<String, ToolInvoker> invokers;

    public AssistantBackendToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public List<Map<String, Object>> listToolSummaries() {
        Map<String, ToolInvoker> current = invokers();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ToolInvoker invoker : current.values()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("code", invoker.code);
            item.put("name", invoker.name);
            item.put("description", invoker.description);
            item.put("control", "Only backend allow-listed annotated methods can run. User text is never treated as a method name.");
            result.add(item);
        }
        return result;
    }

    public Object invoke(String code, AssistantPlanRequest request, Map<String, Object> params) {
        ToolInvoker invoker = invokers().get(code);
        if (invoker == null) {
            throw new IllegalArgumentException("Unsupported assistant backend tool: " + code);
        }
        try {
            return invoker.method.invoke(invoker.bean, request, params == null ? Collections.<String, Object>emptyMap() : params);
        } catch (Exception ex) {
            throw new IllegalStateException("Assistant backend tool failed: " + code, ex);
        }
    }

    private Map<String, ToolInvoker> invokers() {
        Map<String, ToolInvoker> current = invokers;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (invokers == null) {
                invokers = scanInvokers();
            }
            return invokers;
        }
    }

    private Map<String, ToolInvoker> scanInvokers() {
        Map<String, ToolInvoker> result = new LinkedHashMap<String, ToolInvoker>();
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            Object bean;
            try {
                bean = applicationContext.getBean(beanName);
            } catch (Exception ignored) {
                continue;
            }
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            for (Method method : targetClass.getMethods()) {
                AssistantBackendTool annotation = method.getAnnotation(AssistantBackendTool.class);
                if (annotation == null) {
                    continue;
                }
                validateToolMethod(annotation.code(), method);
                if (result.containsKey(annotation.code())) {
                    throw new IllegalStateException("Duplicate assistant backend tool code: " + annotation.code());
                }
                result.put(annotation.code(), new ToolInvoker(bean, method, annotation));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private void validateToolMethod(String code, Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length != 2
                || !AssistantPlanRequest.class.equals(parameterTypes[0])
                || !Map.class.isAssignableFrom(parameterTypes[1])) {
            throw new IllegalStateException("Assistant backend tool method must be (AssistantPlanRequest, Map): " + code);
        }
    }

    private static final class ToolInvoker {
        private final Object bean;
        private final Method method;
        private final String code;
        private final String name;
        private final String description;

        private ToolInvoker(Object bean, Method method, AssistantBackendTool annotation) {
            this.bean = bean;
            this.method = method;
            this.code = annotation.code();
            this.name = annotation.name();
            this.description = annotation.description();
        }
    }
}
