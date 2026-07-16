package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.AlertRuleType;
import com.jdragon.studio.dto.enums.AlertSeverity;
import com.jdragon.studio.dto.enums.AlertSubjectType;
import com.jdragon.studio.dto.model.AlertOptionView;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AlertRuleDefinitionRegistry {

    private final Map<AlertRuleType, Definition> definitions = new EnumMap<AlertRuleType, Definition>(AlertRuleType.class);

    public AlertRuleDefinitionRegistry() {
        register(AlertRuleType.EXECUTION_FAILED, "执行失败", "一次失败或错误即触发",
                subjects(AlertSubjectType.COLLECTION_TASK, AlertSubjectType.QUALITY_TASK, AlertSubjectType.WORKFLOW));
        register(AlertRuleType.CONSECUTIVE_FAILURES, "连续失败", "连续多次执行失败",
                subjects(AlertSubjectType.COLLECTION_TASK, AlertSubjectType.QUALITY_TASK, AlertSubjectType.WORKFLOW),
                integerField("consecutiveCount", "连续失败次数", 3, 2, 20));
        register(AlertRuleType.RUN_TIMEOUT, "运行超时", "运行时间超过阈值",
                subjects(AlertSubjectType.COLLECTION_TASK, AlertSubjectType.QUALITY_TASK, AlertSubjectType.WORKFLOW),
                integerField("durationMinutes", "超时分钟数", 30, 1, 10080));
        register(AlertRuleType.SERVICE_FAILURE_RATE, "服务失败率", "小时统计桶失败率超过阈值",
                subjects(AlertSubjectType.DATA_SERVICE, AlertSubjectType.DATA_INGESTION_SERVICE, AlertSubjectType.PROTOCOL_CONVERSION_SERVICE),
                enumField("windowHours", "统计小时数", 1, Arrays.asList(1, 6, 24)),
                integerField("failureRatePercent", "失败率百分比", 20, 1, 100),
                integerField("minimumRequests", "最小请求数", 20, 1, 1000000));
        register(AlertRuleType.INVOCATION_WRITE_FAILED, "接入或转换调用失败", "写入失败或存在失败记录即触发",
                subjects(AlertSubjectType.DATA_INGESTION_SERVICE, AlertSubjectType.PROTOCOL_CONVERSION_SERVICE));
        register(AlertRuleType.WORKER_OFFLINE, "Worker 离线", "Worker 组在宽限时间内没有有效心跳",
                subjects(AlertSubjectType.WORKER_GROUP),
                integerField("offlineSeconds", "离线秒数", 120, 30, 3600));
        register(AlertRuleType.QUEUE_BACKLOG, "队列积压", "排队数量和最老等待时间同时超过阈值",
                subjects(AlertSubjectType.PROJECT_QUEUE, AlertSubjectType.WORKER_GROUP),
                integerField("queuedCount", "排队数量", 20, 1, 100000),
                integerField("oldestWaitMinutes", "最老等待分钟数", 5, 0, 1440));
        register(AlertRuleType.SCHEDULE_DELAY, "调度延迟", "超过计划触发时间仍未开始执行",
                subjects(AlertSubjectType.COLLECTION_TASK, AlertSubjectType.QUALITY_TASK, AlertSubjectType.WORKFLOW),
                integerField("delayMinutes", "延迟分钟数", 10, 1, 10080));
        register(AlertRuleType.LOG_UPLOAD_FAILED, "日志上传失败", "运行或开放服务日志归档失败",
                subjects(AlertSubjectType.LOG_STORAGE),
                multiEnumField("domains", "日志子域",
                        Arrays.asList("RUN_LOG", "DATA_SERVICE_LOG", "DATA_INGESTION_LOG", "PROTOCOL_CONVERSION_LOG"),
                        Arrays.asList("RUN_LOG", "DATA_SERVICE_LOG", "DATA_INGESTION_LOG", "PROTOCOL_CONVERSION_LOG")));
    }

    public List<AlertOptionView> options() {
        List<AlertOptionView> result = new ArrayList<AlertOptionView>();
        for (AlertRuleType type : AlertRuleType.values()) {
            Definition definition = definitions.get(type);
            AlertOptionView view = new AlertOptionView();
            view.setCode(type.name());
            view.setLabel(definition.label);
            view.setDescription(definition.description);
            view.setDefaultSeverity(definition.defaultSeverity.name());
            List<String> subjectTypes = new ArrayList<String>();
            for (AlertSubjectType subjectType : definition.subjectTypes) {
                subjectTypes.add(subjectType.name());
            }
            view.setSubjectTypes(subjectTypes);
            Map<String, Object> schema = new LinkedHashMap<String, Object>();
            List<Map<String, Object>> fields = new ArrayList<Map<String, Object>>();
            Map<String, Object> defaults = new LinkedHashMap<String, Object>();
            for (FieldDefinition field : definition.fields.values()) {
                fields.add(field.schema());
                defaults.put(field.name, field.defaultValue);
            }
            schema.put("fields", fields);
            view.setConditionSchema(schema);
            view.setDefaults(defaults);
            result.add(view);
        }
        return result;
    }

    public Map<String, Object> validateAndNormalize(String ruleTypeValue,
                                                     String subjectTypeValue,
                                                     Map<String, Object> condition) {
        AlertRuleType ruleType = parseRuleType(ruleTypeValue);
        AlertSubjectType subjectType = parseSubjectType(subjectTypeValue);
        Definition definition = definitions.get(ruleType);
        if (!definition.subjectTypes.contains(subjectType)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Subject type " + subjectType.name() + " is not supported by " + ruleType.name());
        }
        Map<String, Object> source = condition == null ? Collections.<String, Object>emptyMap() : condition;
        for (String name : source.keySet()) {
            if (!definition.fields.containsKey(name)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unknown alert condition field: " + name);
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<String, Object>();
        for (FieldDefinition field : definition.fields.values()) {
            normalized.put(field.name, field.normalize(source.get(field.name)));
        }
        return normalized;
    }

    public AlertRuleType parseRuleType(String value) {
        try {
            return AlertRuleType.valueOf(normalize(value));
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported alert rule type: " + value);
        }
    }

    public AlertSubjectType parseSubjectType(String value) {
        try {
            return AlertSubjectType.valueOf(normalize(value));
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported alert subject type: " + value);
        }
    }

    public boolean supportsResourceOwner(String subjectType) {
        AlertSubjectType parsed = parseSubjectType(subjectType);
        return parsed == AlertSubjectType.COLLECTION_TASK
                || parsed == AlertSubjectType.QUALITY_TASK
                || parsed == AlertSubjectType.WORKFLOW
                || parsed == AlertSubjectType.DATA_SERVICE
                || parsed == AlertSubjectType.DATA_INGESTION_SERVICE
                || parsed == AlertSubjectType.PROTOCOL_CONVERSION_SERVICE;
    }

    private void register(AlertRuleType type, String label, String description,
                          Set<AlertSubjectType> subjectTypes, FieldDefinition... fields) {
        Definition definition = new Definition(label, description, subjectTypes, defaultSeverity(type));
        if (fields != null) {
            for (FieldDefinition field : fields) {
                definition.fields.put(field.name, field);
            }
        }
        definitions.put(type, definition);
    }

    private AlertSeverity defaultSeverity(AlertRuleType type) {
        if (type == AlertRuleType.CONSECUTIVE_FAILURES
                || type == AlertRuleType.SERVICE_FAILURE_RATE
                || type == AlertRuleType.WORKER_OFFLINE) {
            return AlertSeverity.CRITICAL;
        }
        return AlertSeverity.WARNING;
    }

    private Set<AlertSubjectType> subjects(AlertSubjectType... values) {
        return new LinkedHashSet<AlertSubjectType>(Arrays.asList(values));
    }

    private FieldDefinition integerField(String name, String label, int defaultValue, int min, int max) {
        return new FieldDefinition(name, label, "integer", Integer.valueOf(defaultValue), Integer.valueOf(min), Integer.valueOf(max), null);
    }

    private FieldDefinition enumField(String name, String label, int defaultValue, List<Integer> options) {
        return new FieldDefinition(name, label, "enum", Integer.valueOf(defaultValue), null, null, new ArrayList<Object>(options));
    }

    private FieldDefinition multiEnumField(String name, String label, List<String> defaultValue, List<String> options) {
        return new FieldDefinition(name, label, "multi-enum", new ArrayList<String>(defaultValue), null, null, new ArrayList<Object>(options));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static final class Definition {
        private final String label;
        private final String description;
        private final AlertSeverity defaultSeverity;
        private final Set<AlertSubjectType> subjectTypes;
        private final Map<String, FieldDefinition> fields = new LinkedHashMap<String, FieldDefinition>();

        private Definition(String label, String description, Set<AlertSubjectType> subjectTypes,
                           AlertSeverity defaultSeverity) {
            this.label = label;
            this.description = description;
            this.subjectTypes = subjectTypes;
            this.defaultSeverity = defaultSeverity;
        }
    }

    private static final class FieldDefinition {
        private final String name;
        private final String label;
        private final String type;
        private final Object defaultValue;
        private final Integer min;
        private final Integer max;
        private final List<Object> options;

        private FieldDefinition(String name, String label, String type, Object defaultValue,
                                Integer min, Integer max, List<Object> options) {
            this.name = name;
            this.label = label;
            this.type = type;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
            this.options = options;
        }

        private Map<String, Object> schema() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("name", name);
            result.put("label", label);
            result.put("type", type);
            result.put("defaultValue", defaultValue);
            if (min != null) {
                result.put("min", min);
            }
            if (max != null) {
                result.put("max", max);
            }
            if (options != null) {
                result.put("options", options);
            }
            return result;
        }

        private Object normalize(Object raw) {
            Object value = raw == null ? defaultValue : raw;
            if ("integer".equals(type)) {
                int number = exactInteger(value, name + " must be an integer");
                if ((min != null && number < min.intValue()) || (max != null && number > max.intValue())) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST,
                            name + " must be between " + min + " and " + max);
                }
                return Integer.valueOf(number);
            }
            if ("enum".equals(type)) {
                int number = exactInteger(value, name + " has an invalid value");
                if (options == null || !options.contains(Integer.valueOf(number))) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST, name + " has an unsupported value");
                }
                return Integer.valueOf(number);
            }
            if ("multi-enum".equals(type)) {
                if (!(value instanceof Iterable<?>)) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST, name + " must be an array");
                }
                List<String> result = new ArrayList<String>();
                for (Object item : (Iterable<?>) value) {
                    String normalized = item == null ? "" : String.valueOf(item).trim().toUpperCase();
                    if (!options.contains(normalized)) {
                        throw new StudioException(StudioErrorCode.BAD_REQUEST, name + " contains an unsupported value: " + normalized);
                    }
                    if (!result.contains(normalized)) {
                        result.add(normalized);
                    }
                }
                if (result.isEmpty()) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST, name + " must not be empty");
                }
                return result;
            }
            return value;
        }

        private int exactInteger(Object value, String message) {
            try {
                return new BigDecimal(String.valueOf(value)).intValueExact();
            } catch (Exception ex) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
            }
        }
    }
}
