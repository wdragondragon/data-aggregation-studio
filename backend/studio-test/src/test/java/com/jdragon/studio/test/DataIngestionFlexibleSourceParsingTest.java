package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.enums.DataIngestionRequestFormat;
import com.jdragon.studio.dto.enums.DataIngestionSourcePosition;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.model.DataIngestionFieldMapping;
import com.jdragon.studio.dto.model.DataIngestionServiceView;
import com.jdragon.studio.infra.service.DataIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataIngestionFlexibleSourceParsingTest {

    @Test
    void shouldParseJsonRowsWhenMappingsUseJsonBody() {
        DataIngestionService service = newService();
        DataIngestionServiceView view = new DataIngestionServiceView();
        view.setRequestFormat(DataIngestionRequestFormat.FORM);
        view.setDataNodePath("payload.items");

        Map<String, Object> first = new LinkedHashMap<String, Object>();
        first.put("name", "Alice");
        Map<String, Object> second = new LinkedHashMap<String, Object>();
        second.put("name", "Bob");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("items", Arrays.asList(first, second));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("payload", payload);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = ReflectionTestUtils.invokeMethod(service,
                "parseSourceRows",
                view,
                body,
                Arrays.asList(mapping(DataIngestionSourcePosition.BODY, "name"),
                        mapping(DataIngestionSourcePosition.HEADER, "source")));

        assertEquals(2, rows.size());
        assertEquals("Alice", rows.get(0).get("name"));
        assertEquals("Bob", rows.get(1).get("name"));
    }

    @Test
    void shouldUseSingleEmptyRowWhenMappingsDoNotUseJsonBody() {
        DataIngestionService service = newService();
        DataIngestionServiceView view = new DataIngestionServiceView();
        view.setRequestFormat(DataIngestionRequestFormat.JSON);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = ReflectionTestUtils.invokeMethod(service,
                "parseSourceRows",
                view,
                null,
                Arrays.asList(mapping(DataIngestionSourcePosition.FORM, "name"),
                        mapping(DataIngestionSourcePosition.QUERY, "tenant"),
                        mapping(DataIngestionSourcePosition.HEADER, "source")));

        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).size());
    }

    private static DataIngestionService newService() {
        return new DataIngestionService(null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper());
    }

    private static DataIngestionFieldMapping mapping(DataIngestionSourcePosition position, String field) {
        DataIngestionFieldMapping mapping = new DataIngestionFieldMapping();
        mapping.setSourcePosition(position);
        mapping.setSourceField(field);
        mapping.setTargetField(field);
        mapping.setValueType(FieldValueType.STRING);
        return mapping;
    }
}
