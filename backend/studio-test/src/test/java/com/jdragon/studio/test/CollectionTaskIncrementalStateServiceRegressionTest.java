package com.jdragon.studio.test;

import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.service.CollectionTaskIncrementalStateService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectionTaskIncrementalStateServiceRegressionTest {

    @Test
    void shouldSanitizeLocalDateTimeCursorMetadataBeforePersistingSourceBindings() {
        CollectionTaskDefinitionMapper definitionMapper = mock(CollectionTaskDefinitionMapper.class);
        CollectionTaskDefinitionEntity entity = new CollectionTaskDefinitionEntity();
        entity.setId(200L);
        Map<String, Object> incremental = new LinkedHashMap<String, Object>();
        incremental.put("enabled", Boolean.TRUE);
        incremental.put("incrColumn", "id");
        incremental.put("incrModel", ">");
        incremental.put("pkValue", Long.valueOf(1L));
        incremental.put("lastUpdatedAt", LocalDateTime.of(2026, 5, 1, 17, 10, 0));
        Map<String, Object> sourceBinding = new LinkedHashMap<String, Object>();
        sourceBinding.put("sourceAlias", "src1");
        sourceBinding.put("incremental", incremental);
        entity.setSourceBindingsJson(Collections.singletonList(sourceBinding));
        when(definitionMapper.selectById(eq(200L))).thenReturn(entity);

        CollectionTaskIncrementalStateService service = new CollectionTaskIncrementalStateService(definitionMapper);
        Map<String, Object> cursor = new LinkedHashMap<String, Object>();
        cursor.put("sourceAlias", "src1");
        cursor.put("pkValue", Long.valueOf(42L));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("incrementalCursors", Collections.singletonMap("src1", cursor));

        service.updateFromExecutionResult(200L, 300L, result);

        ArgumentCaptor<CollectionTaskDefinitionEntity> captor = ArgumentCaptor.forClass(CollectionTaskDefinitionEntity.class);
        verify(definitionMapper).updateById(captor.capture());
        Object updatedAt = firstIncremental(captor.getValue()).get("lastUpdatedAt");
        assertTrue(updatedAt instanceof String);
        Map<String, Object> updatedIncremental = firstIncremental(captor.getValue());
        assertEquals(Long.valueOf(42L), updatedIncremental.get("pkValue"));
        assertEquals(Long.valueOf(42L), findCursorState(updatedIncremental, "id").get("pkValue"));
    }

    @Test
    void shouldKeepIncrementalCursorStatesByFieldWhenAnotherFieldIsUpdated() {
        CollectionTaskDefinitionMapper definitionMapper = mock(CollectionTaskDefinitionMapper.class);
        CollectionTaskDefinitionEntity entity = new CollectionTaskDefinitionEntity();
        entity.setId(200L);

        Map<String, Object> idCursor = new LinkedHashMap<String, Object>();
        idCursor.put("incrColumn", "id");
        idCursor.put("incrModel", ">");
        idCursor.put("valueType", "LONG");
        idCursor.put("pkValue", Long.valueOf(42L));
        idCursor.put("lastRunRecordId", Long.valueOf(300L));
        idCursor.put("lastUpdatedAt", "2026-05-01 17:10:00");

        Map<String, Object> incremental = new LinkedHashMap<String, Object>();
        incremental.put("enabled", Boolean.TRUE);
        incremental.put("incrColumn", "updated_at");
        incremental.put("incrModel", ">");
        incremental.put("cursorStates", Collections.singletonList(idCursor));

        Map<String, Object> sourceBinding = new LinkedHashMap<String, Object>();
        sourceBinding.put("sourceAlias", "src1");
        sourceBinding.put("incremental", incremental);
        entity.setSourceBindingsJson(Collections.singletonList(sourceBinding));
        when(definitionMapper.selectById(eq(200L))).thenReturn(entity);

        CollectionTaskIncrementalStateService service = new CollectionTaskIncrementalStateService(definitionMapper);
        Map<String, Object> updatedAtCursor = new LinkedHashMap<String, Object>();
        updatedAtCursor.put("sourceAlias", "src1");
        updatedAtCursor.put("incrColumn", "updated_at");
        updatedAtCursor.put("incrModel", ">");
        updatedAtCursor.put("valueType", "STRING");
        updatedAtCursor.put("pkValue", "2026-05-03 10:00:00");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("incrementalCursors", Collections.singletonMap("src1", updatedAtCursor));

        service.updateFromExecutionResult(200L, 301L, result);

        ArgumentCaptor<CollectionTaskDefinitionEntity> captor = ArgumentCaptor.forClass(CollectionTaskDefinitionEntity.class);
        verify(definitionMapper).updateById(captor.capture());
        Map<String, Object> updatedIncremental = firstIncremental(captor.getValue());
        assertEquals("2026-05-03 10:00:00", updatedIncremental.get("pkValue"));
        assertEquals(Long.valueOf(42L), findCursorState(updatedIncremental, "id").get("pkValue"));
        assertEquals("2026-05-03 10:00:00", findCursorState(updatedIncremental, "updated_at").get("pkValue"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstIncremental(CollectionTaskDefinitionEntity entity) {
        List<Map<String, Object>> sourceBindings = entity.getSourceBindingsJson();
        return (Map<String, Object>) sourceBindings.get(0).get("incremental");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findCursorState(Map<String, Object> incremental, String incrColumn) {
        List<Map<String, Object>> cursorStates = (List<Map<String, Object>>) incremental.get("cursorStates");
        for (Map<String, Object> cursorState : cursorStates) {
            if (incrColumn.equals(cursorState.get("incrColumn"))) {
                return cursorState;
            }
        }
        throw new AssertionError("Cursor state not found: " + incrColumn);
    }
}
