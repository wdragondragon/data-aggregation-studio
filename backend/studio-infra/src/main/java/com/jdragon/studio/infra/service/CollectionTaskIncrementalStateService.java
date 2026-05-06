package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CollectionTaskIncrementalStateService {

    private static final DateTimeFormatter CURSOR_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DEFAULT_INCR_MODEL = ">";

    private final CollectionTaskDefinitionMapper definitionMapper;

    public CollectionTaskIncrementalStateService(CollectionTaskDefinitionMapper definitionMapper) {
        this.definitionMapper = definitionMapper;
    }

    @Transactional
    public void updateFromExecutionResult(Long collectionTaskId,
                                          Long runRecordId,
                                          Map<String, Object> result) {
        if (collectionTaskId == null || result == null || result.isEmpty()) {
            return;
        }
        Map<String, Object> cursors = valueAsMap(result.get("incrementalCursors"));
        if (cursors.isEmpty()) {
            return;
        }
        CollectionTaskDefinitionEntity entity = definitionMapper.selectById(collectionTaskId);
        if (entity == null) {
            return;
        }
        List<Map<String, Object>> sourceBindings = valueAsMutableMapList(entity.getSourceBindingsJson());
        if (sourceBindings.isEmpty()) {
            return;
        }
        boolean updated = false;
        LocalDateTime updatedAt = LocalDateTime.now();
        for (Map.Entry<String, Object> entry : cursors.entrySet()) {
            Map<String, Object> cursor = valueAsMap(entry.getValue());
            String sourceAlias = resolveSourceAlias(entry.getKey(), cursor);
            Object pkValue = cursor.isEmpty() ? entry.getValue() : cursor.get("pkValue");
            if (isBlankValue(sourceAlias) || isBlankValue(pkValue)) {
                continue;
            }
            Map<String, Object> sourceBinding = findSourceBinding(sourceBindings, sourceAlias);
            if (sourceBinding == null) {
                continue;
            }
            Map<String, Object> incremental = valueAsMap(sourceBinding.get("incremental"));
            incremental.put("enabled", Boolean.TRUE);
            String incrColumn = firstNonBlank(cursor.get("incrColumn"), incremental.get("incrColumn"));
            String incrModel = normalizeIncrModel(firstNonBlank(cursor.get("incrModel"), incremental.get("incrModel")));
            if (isBlankValue(incrColumn)) {
                continue;
            }
            Map<String, Object> cursorState = upsertCursorState(incremental, incrColumn, incrModel);
            cursorState.put("incrColumn", incrColumn);
            cursorState.put("incrModel", incrModel);
            copyIfPresent(cursor, cursorState, "valueType");
            cursorState.put("pkValue", pkValue);
            cursorState.put("lastRunRecordId", runRecordId);
            cursorState.put("lastUpdatedAt", updatedAt.format(CURSOR_TIME_FORMATTER));
            copyCursorStateProjection(cursorState, incremental);
            sourceBinding.put("incremental", incremental);
            updated = true;
        }
        if (updated) {
            entity.setSourceBindingsJson(sanitizeJsonMapList(sourceBindings));
            definitionMapper.updateById(entity);
        }
    }

    private Map<String, Object> upsertCursorState(Map<String, Object> incremental,
                                                  String incrColumn,
                                                  String incrModel) {
        List<Map<String, Object>> cursorStates = valueAsMutableMapList(incremental.get("cursorStates"));
        Map<String, Object> cursorState = findCursorState(cursorStates, incrColumn, incrModel);
        if (cursorState == null) {
            cursorState = new LinkedHashMap<String, Object>();
            cursorStates.add(cursorState);
        }
        incremental.put("cursorStates", cursorStates);
        return cursorState;
    }

    private Map<String, Object> findCursorState(List<Map<String, Object>> cursorStates,
                                                String incrColumn,
                                                String incrModel) {
        for (Map<String, Object> cursorState : cursorStates) {
            if (sameScope(cursorState.get("incrColumn"), cursorState.get("incrModel"), incrColumn, incrModel)) {
                return cursorState;
            }
        }
        return null;
    }

    private void copyCursorStateProjection(Map<String, Object> cursorState, Map<String, Object> incremental) {
        copyIfPresent(cursorState, incremental, "incrColumn");
        copyIfPresent(cursorState, incremental, "incrModel");
        copyIfPresent(cursorState, incremental, "valueType");
        copyIfPresent(cursorState, incremental, "pkValue");
        copyIfPresent(cursorState, incremental, "lastRunRecordId");
        copyIfPresent(cursorState, incremental, "lastUpdatedAt");
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (!isBlankValue(value)) {
            target.put(key, value);
        }
    }

    private Map<String, Object> findSourceBinding(List<Map<String, Object>> sourceBindings, String sourceAlias) {
        for (Map<String, Object> sourceBinding : sourceBindings) {
            Object candidate = sourceBinding.get("sourceAlias");
            if (candidate != null && sourceAlias.equalsIgnoreCase(String.valueOf(candidate))) {
                return sourceBinding;
            }
        }
        return null;
    }

    private String resolveSourceAlias(String fallback, Map<String, Object> cursor) {
        Object sourceAlias = cursor.get("sourceAlias");
        if (!isBlankValue(sourceAlias)) {
            return String.valueOf(sourceAlias);
        }
        Object id = cursor.get("id");
        if (!isBlankValue(id)) {
            return String.valueOf(id);
        }
        return fallback;
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (!isBlankValue(value)) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private boolean sameScope(Object leftColumn, Object leftModel, Object rightColumn, Object rightModel) {
        return sameText(leftColumn, rightColumn)
                && normalizeIncrModel(leftModel).equals(normalizeIncrModel(rightModel));
    }

    private boolean sameText(Object left, Object right) {
        String leftText = left == null ? "" : String.valueOf(left).trim();
        String rightText = right == null ? "" : String.valueOf(right).trim();
        return leftText.equals(rightText);
    }

    private String normalizeIncrModel(Object incrModel) {
        if (isBlankValue(incrModel)) {
            return DEFAULT_INCR_MODEL;
        }
        return String.valueOf(incrModel).trim();
    }

    private boolean isBlankValue(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
    }

    private List<Map<String, Object>> valueAsMutableMapList(Object value) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (!(value instanceof List<?>)) {
            return result;
        }
        for (Object item : (List<?>) value) {
            Map<String, Object> map = valueAsMap(item);
            if (!map.isEmpty()) {
                result.add(map);
            }
        }
        return result;
    }

    private Map<String, Object> valueAsMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (!(value instanceof Map<?, ?>)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private List<Map<String, Object>> sanitizeJsonMapList(List<Map<String, Object>> values) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        if (values == null) {
            return result;
        }
        for (Map<String, Object> value : values) {
            result.add(sanitizeJsonMap(value));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeJsonMap(Map<String, Object> value) {
        Object sanitized = sanitizeJsonValue(value);
        if (sanitized instanceof Map<?, ?>) {
            return (Map<String, Object>) sanitized;
        }
        return new LinkedHashMap<String, Object>();
    }

    private Object sanitizeJsonValue(Object value) {
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).format(CURSOR_TIME_FORMATTER);
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), sanitizeJsonValue(entry.getValue()));
                }
            }
            return result;
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                result.add(sanitizeJsonValue(item));
            }
            return result;
        }
        return value;
    }
}
