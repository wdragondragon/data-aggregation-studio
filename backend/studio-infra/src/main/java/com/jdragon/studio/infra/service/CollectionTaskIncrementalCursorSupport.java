package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.CollectionIncrementalCursorState;
import com.jdragon.studio.dto.model.CollectionIncrementalDefinition;
import com.jdragon.studio.dto.model.CollectionTaskSourceBinding;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CollectionTaskIncrementalCursorSupport {

    static final class ResetResult {
        private final List<Map<String, Object>> sourceBindings;
        private final boolean sourceAliasMissing;
        private final boolean changed;

        private ResetResult(List<Map<String, Object>> sourceBindings,
                            boolean sourceAliasMissing,
                            boolean changed) {
            this.sourceBindings = sourceBindings;
            this.sourceAliasMissing = sourceAliasMissing;
            this.changed = changed;
        }

        List<Map<String, Object>> getSourceBindings() {
            return sourceBindings;
        }

        boolean isSourceAliasMissing() {
            return sourceAliasMissing;
        }

        boolean isChanged() {
            return changed;
        }
    }

    private final ObjectMapper objectMapper;

    CollectionTaskIncrementalCursorSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void protectSystemIncrementalCursors(CollectionTaskDefinitionEntity existingEntity,
                                         List<CollectionTaskSourceBinding> sourceBindings) {
        clearIncomingSystemIncrementalCursorFields(sourceBindings);
        if (existingEntity == null || sourceBindings == null || sourceBindings.isEmpty()) {
            return;
        }
        Map<String, CollectionIncrementalDefinition> existingIncrementals = existingIncrementalsByAlias(existingEntity);
        if (existingIncrementals.isEmpty()) {
            return;
        }
        for (CollectionTaskSourceBinding sourceBinding : sourceBindings) {
            if (sourceBinding == null || sourceBinding.getIncremental() == null) {
                continue;
            }
            CollectionIncrementalDefinition existing = existingIncrementals.get(normalizeAlias(sourceBinding.getSourceAlias()));
            if (existing == null) {
                continue;
            }
            copySystemIncrementalCursorFields(existing, sourceBinding.getIncremental());
        }
    }

    ResetResult resetSystemIncrementalCursorFields(List<Map<String, Object>> sourceBindingValue,
                                                   String sourceAlias,
                                                   String incrColumn,
                                                   String incrModel) {
        List<Map<String, Object>> sourceBindings = valueAsMutableMapList(sourceBindingValue);
        boolean resetAll = sourceAlias == null || sourceAlias.trim().isEmpty();
        boolean matched = false;
        boolean changed = false;
        for (Map<String, Object> sourceBinding : sourceBindings) {
            if (!resetAll && !sameAlias(sourceBinding.get("sourceAlias"), sourceAlias)) {
                continue;
            }
            matched = true;
            Map<String, Object> incremental = valueAsMap(sourceBinding.get("incremental"));
            if (removeSystemIncrementalCursorFields(incremental, incrColumn, incrModel)) {
                sourceBinding.put("incremental", incremental);
                changed = true;
            }
        }
        return new ResetResult(sourceBindings, !resetAll && !matched, changed);
    }

    void normalizeSourceBindingsForView(List<CollectionTaskSourceBinding> sourceBindings) {
        if (sourceBindings == null) {
            return;
        }
        for (CollectionTaskSourceBinding sourceBinding : sourceBindings) {
            if (sourceBinding == null || sourceBinding.getIncremental() == null) {
                continue;
            }
            CollectionIncrementalDefinition incremental = sourceBinding.getIncremental();
            normalizeCursorStates(incremental);
            refreshIncrementalCursorProjection(incremental);
        }
    }

    private void clearIncomingSystemIncrementalCursorFields(List<CollectionTaskSourceBinding> sourceBindings) {
        if (sourceBindings == null) {
            return;
        }
        for (CollectionTaskSourceBinding sourceBinding : sourceBindings) {
            if (sourceBinding == null || sourceBinding.getIncremental() == null) {
                continue;
            }
            clearSystemIncrementalCursorFields(sourceBinding.getIncremental());
        }
    }

    private void clearSystemIncrementalCursorFields(CollectionIncrementalDefinition incremental) {
        clearIncrementalCursorProjection(incremental);
        incremental.setCursorStates(new ArrayList<CollectionIncrementalCursorState>());
    }

    private void refreshIncrementalCursorProjection(CollectionIncrementalDefinition incremental) {
        CollectionIncrementalCursorState currentState = findCursorState(incremental.getCursorStates(),
                incremental.getIncrColumn(),
                normalizeIncrModel(incremental.getIncrModel()));
        clearIncrementalCursorProjection(incremental);
        if (currentState == null) {
            return;
        }
        incremental.setPkValue(currentState.getPkValue());
        incremental.setValueType(currentState.getValueType());
        incremental.setLastRunRecordId(currentState.getLastRunRecordId());
        incremental.setLastUpdatedAt(currentState.getLastUpdatedAt());
    }

    private void clearIncrementalCursorProjection(CollectionIncrementalDefinition incremental) {
        incremental.setPkValue(null);
        incremental.setValueType(null);
        incremental.setLastRunRecordId(null);
        incremental.setLastUpdatedAt(null);
    }

    private Map<String, CollectionIncrementalDefinition> existingIncrementalsByAlias(CollectionTaskDefinitionEntity entity) {
        Map<String, CollectionIncrementalDefinition> result = new LinkedHashMap<String, CollectionIncrementalDefinition>();
        for (Map<String, Object> sourceBinding : valueAsMutableMapList(entity.getSourceBindingsJson())) {
            String sourceAlias = normalizeAlias(sourceBinding.get("sourceAlias"));
            if (sourceAlias.isEmpty()) {
                continue;
            }
            Map<String, Object> incremental = valueAsMap(sourceBinding.get("incremental"));
            if (incremental.isEmpty()) {
                continue;
            }
            CollectionIncrementalDefinition definition = toIncrementalDefinition(incremental);
            if (definition != null) {
                normalizeCursorStates(definition);
                result.put(sourceAlias, definition);
            }
        }
        return result;
    }

    private CollectionIncrementalDefinition toIncrementalDefinition(Map<String, Object> incremental) {
        try {
            return objectMapper.convertValue(incremental, CollectionIncrementalDefinition.class);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String normalizeIncrModel(String incrModel) {
        return incrModel == null || incrModel.trim().isEmpty() ? ">" : incrModel.trim();
    }

    private String normalizeIncrModel(Object incrModel) {
        return incrModel == null || String.valueOf(incrModel).trim().isEmpty() ? ">" : String.valueOf(incrModel).trim();
    }

    private void copySystemIncrementalCursorFields(CollectionIncrementalDefinition existing,
                                                   CollectionIncrementalDefinition incoming) {
        List<CollectionIncrementalCursorState> cursorStates = copyCursorStates(existing.getCursorStates());
        incoming.setCursorStates(cursorStates);
        CollectionIncrementalCursorState currentState = findCursorState(cursorStates,
                incoming.getIncrColumn(),
                normalizeIncrModel(incoming.getIncrModel()));
        if (currentState == null) {
            return;
        }
        incoming.setPkValue(currentState.getPkValue());
        incoming.setValueType(currentState.getValueType());
        incoming.setLastRunRecordId(currentState.getLastRunRecordId());
        incoming.setLastUpdatedAt(currentState.getLastUpdatedAt());
    }

    private void normalizeCursorStates(CollectionIncrementalDefinition incremental) {
        List<CollectionIncrementalCursorState> cursorStates = copyCursorStates(incremental.getCursorStates());
        if (cursorStates.isEmpty()
                && !isBlankOption(incremental.getPkValue())
                && !isBlankOption(incremental.getIncrColumn())) {
            CollectionIncrementalCursorState state = new CollectionIncrementalCursorState();
            state.setIncrColumn(incremental.getIncrColumn());
            state.setIncrModel(normalizeIncrModel(incremental.getIncrModel()));
            state.setPkValue(incremental.getPkValue());
            state.setValueType(incremental.getValueType());
            state.setLastRunRecordId(incremental.getLastRunRecordId());
            state.setLastUpdatedAt(incremental.getLastUpdatedAt());
            cursorStates.add(state);
        }
        incremental.setCursorStates(cursorStates);
    }

    private List<CollectionIncrementalCursorState> copyCursorStates(List<CollectionIncrementalCursorState> cursorStates) {
        List<CollectionIncrementalCursorState> result = new ArrayList<CollectionIncrementalCursorState>();
        if (cursorStates == null) {
            return result;
        }
        for (CollectionIncrementalCursorState cursorState : cursorStates) {
            if (cursorState == null || isBlankOption(cursorState.getIncrColumn())) {
                continue;
            }
            CollectionIncrementalCursorState copy = new CollectionIncrementalCursorState();
            copy.setIncrColumn(cursorState.getIncrColumn());
            copy.setIncrModel(normalizeIncrModel(cursorState.getIncrModel()));
            copy.setPkValue(cursorState.getPkValue());
            copy.setValueType(cursorState.getValueType());
            copy.setLastRunRecordId(cursorState.getLastRunRecordId());
            copy.setLastUpdatedAt(cursorState.getLastUpdatedAt());
            result.add(copy);
        }
        return result;
    }

    private CollectionIncrementalCursorState findCursorState(List<CollectionIncrementalCursorState> cursorStates,
                                                             String incrColumn,
                                                             String incrModel) {
        if (cursorStates == null || isBlankOption(incrColumn)) {
            return null;
        }
        for (CollectionIncrementalCursorState cursorState : cursorStates) {
            if (cursorState == null) {
                continue;
            }
            if (sameIncrementalScope(cursorState.getIncrColumn(), cursorState.getIncrModel(), incrColumn, incrModel)) {
                return cursorState;
            }
        }
        return null;
    }

    private boolean sameIncrementalScope(Object leftColumn, Object leftModel, Object rightColumn, Object rightModel) {
        return sameOption(leftColumn, rightColumn)
                && sameOption(normalizeIncrModel(leftModel), normalizeIncrModel(rightModel));
    }

    private boolean sameOption(Object left, Object right) {
        String leftText = left == null ? "" : String.valueOf(left).trim();
        String rightText = right == null ? "" : String.valueOf(right).trim();
        return leftText.equals(rightText);
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (!isBlankOption(value)) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private boolean sameAlias(Object left, Object right) {
        return normalizeAlias(left).equals(normalizeAlias(right));
    }

    private String normalizeAlias(Object alias) {
        return alias == null ? "" : String.valueOf(alias).trim().toLowerCase(Locale.ROOT);
    }

    private boolean removeSystemIncrementalCursorFields(Map<String, Object> incremental,
                                                        String incrColumn,
                                                        String incrModel) {
        boolean changed = false;
        String targetColumn = firstNonBlank(incrColumn, incremental.get("incrColumn"));
        String targetModel = normalizeIncrModel(firstNonBlank(incrModel, incremental.get("incrModel")));
        if (!targetColumn.isEmpty()) {
            List<Map<String, Object>> cursorStates = valueAsMutableMapList(incremental.get("cursorStates"));
            List<Map<String, Object>> retained = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> cursorState : cursorStates) {
                if (sameIncrementalScope(cursorState.get("incrColumn"), cursorState.get("incrModel"), targetColumn, targetModel)) {
                    changed = true;
                    continue;
                }
                retained.add(cursorState);
            }
            if (changed || incremental.containsKey("cursorStates")) {
                incremental.put("cursorStates", retained);
            }
        }
        if (targetColumn.isEmpty() || sameIncrementalScope(incremental.get("incrColumn"), incremental.get("incrModel"), targetColumn, targetModel)) {
            changed = incremental.remove("pkValue") != null || changed;
            changed = incremental.remove("valueType") != null || changed;
            changed = incremental.remove("lastRunRecordId") != null || changed;
            changed = incremental.remove("lastUpdatedAt") != null || changed;
        }
        return changed;
    }

    private boolean isBlankOption(Object value) {
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
}
