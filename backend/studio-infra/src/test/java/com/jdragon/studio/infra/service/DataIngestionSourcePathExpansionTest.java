package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.enums.DataIngestionSourcePosition;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.model.DataIngestionFieldMapping;
import com.jdragon.studio.dto.model.DataIngestionSourceBinding;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataIngestionSourcePathExpansionTest {

    @Test
    void shouldExpandSourcePathAcrossAnyJsonArrayNode() {
        DataIngestionExecutionSupport executionSupport = new DataIngestionExecutionSupport(null, new ObjectMapper());
        Map<String, Object> firstTicketFull = ticketFull("Z202606001",
                Arrays.asList(command("M202606001"), command("M202606002")),
                Arrays.asList(device("D202606001")));
        Map<String, Object> secondTicketFull = ticketFull("Z202606002", null, null);
        Map<String, Object> thirdTicketFull = ticketFull("Z202606003",
                Arrays.asList(command("M202606003")),
                Arrays.asList(device("D202606003")));
        Map<String, Object> body = mapOf("data", mapOf("ticketFullList", Arrays.asList(
                mapOf("ticketFull", Arrays.asList(firstTicketFull, secondTicketFull)),
                mapOf("ticketFull", thirdTicketFull))));

        List<Map<String, Object>> ticketRows = parseRows(executionSupport, body,
                "data.ticketFullList.ticketFull.ticket", "zbid");
        List<Map<String, Object>> commandRows = parseRows(executionSupport, body,
                "data.ticketFullList.ticketFull.commands.command", "mxid");
        List<Map<String, Object>> deviceRows = parseRows(executionSupport, body,
                "data.ticketFullList.ticketFull.devices.device", "cbid");

        assertEquals(3, ticketRows.size());
        assertEquals("Z202606001", ticketRows.get(0).get("zbid"));
        assertEquals("Z202606002", ticketRows.get(1).get("zbid"));
        assertEquals("Z202606003", ticketRows.get(2).get("zbid"));
        assertEquals(3, commandRows.size());
        assertEquals("M202606001", commandRows.get(0).get("mxid"));
        assertEquals("M202606002", commandRows.get(1).get("mxid"));
        assertEquals("M202606003", commandRows.get(2).get("mxid"));
        assertEquals(2, deviceRows.size());
        assertEquals("D202606001", deviceRows.get(0).get("cbid"));
        assertEquals("D202606003", deviceRows.get(1).get("cbid"));
    }

    @Test
    void shouldExpandSourcePathAcrossAnySoapArrayNode() {
        DataIngestionExecutionSupport executionSupport = new DataIngestionExecutionSupport(null, new ObjectMapper());
        WebServiceSupport.ParsedSoapRequest parsed = new WebServiceSupport().parse("""
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ws="http://example.com/ws">
                  <soapenv:Body>
                    <ws:receiveOperationTicketFullRequest>
                      <data>
                        <ticketFullList>
                          <ticketFull>
                            <ticket><zbid>Z202606001</zbid></ticket>
                            <commands>
                              <command><mxid>M202606001</mxid></command>
                              <command><mxid>M202606002</mxid></command>
                            </commands>
                            <devices><device><cbid>D202606001</cbid></device></devices>
                          </ticketFull>
                          <ticketFull>
                            <ticket><zbid>Z202606002</zbid></ticket>
                            <commands/>
                            <devices/>
                          </ticketFull>
                        </ticketFullList>
                      </data>
                    </ws:receiveOperationTicketFullRequest>
                  </soapenv:Body>
                </soapenv:Envelope>
                """);

        List<Map<String, Object>> ticketRows = parseRows(executionSupport, parsed.getBody(),
                "data.ticketFullList.ticketFull.ticket", "zbid");
        List<Map<String, Object>> commandRows = parseRows(executionSupport, parsed.getBody(),
                "data.ticketFullList.ticketFull.commands.command", "mxid");
        List<Map<String, Object>> deviceRows = parseRows(executionSupport, parsed.getBody(),
                "data.ticketFullList.ticketFull.devices.device", "cbid");

        assertEquals(2, ticketRows.size());
        assertEquals("Z202606001", ticketRows.get(0).get("zbid"));
        assertEquals("Z202606002", ticketRows.get(1).get("zbid"));
        assertEquals(2, commandRows.size());
        assertEquals("M202606001", commandRows.get(0).get("mxid"));
        assertEquals("M202606002", commandRows.get(1).get("mxid"));
        assertEquals(1, deviceRows.size());
        assertEquals("D202606001", deviceRows.get(0).get("cbid"));
    }

    private static List<Map<String, Object>> parseRows(DataIngestionExecutionSupport executionSupport,
                                                       Object body,
                                                       String sourcePath,
                                                       String field) {
        DataIngestionFieldMapping mapping = mapping(field);
        return executionSupport.parseSourceRows(binding(sourcePath, mapping),
                new LinkedHashMap<String, Object>(),
                new LinkedHashMap<String, Object>(),
                new LinkedHashMap<String, Object>(),
                body,
                Arrays.asList(mapping));
    }

    private static DataIngestionFieldMapping mapping(String field) {
        DataIngestionFieldMapping mapping = new DataIngestionFieldMapping();
        mapping.setSourcePosition(DataIngestionSourcePosition.BODY);
        mapping.setSourceField(field);
        mapping.setTargetField(field);
        mapping.setValueType(FieldValueType.STRING);
        mapping.setRequired(Boolean.TRUE);
        return mapping;
    }

    private static DataIngestionSourceBinding binding(String sourcePath,
                                                       DataIngestionFieldMapping... mappings) {
        DataIngestionSourceBinding binding = new DataIngestionSourceBinding();
        binding.setSourceCode("source");
        binding.setSourceName("source");
        binding.setSourcePosition(DataIngestionSourcePosition.BODY);
        binding.setSourcePath(sourcePath);
        binding.setFieldMappings(Arrays.asList(mappings));
        binding.setEnabled(Boolean.TRUE);
        return binding;
    }

    private static Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            row.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return row;
    }

    private static Map<String, Object> ticketFull(String zbid, Object commands, Object devices) {
        return mapOf("ticket", mapOf("zbid", zbid),
                "commands", repeatedNode("command", commands),
                "devices", repeatedNode("device", devices));
    }

    private static Map<String, Object> command(String mxid) {
        return mapOf("mxid", mxid);
    }

    private static Map<String, Object> device(String cbid) {
        return mapOf("cbid", cbid);
    }

    private static Map<String, Object> repeatedNode(String key, Object values) {
        Map<String, Object> node = new LinkedHashMap<String, Object>();
        if (values != null) {
            node.put(key, values);
        }
        return node;
    }
}
