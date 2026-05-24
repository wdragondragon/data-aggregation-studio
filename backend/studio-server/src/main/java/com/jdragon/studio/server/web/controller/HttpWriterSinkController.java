package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

@Tag(name = "HTTP Writer Sink", description = "Temporary receiver APIs for HTTP writer smoke tests")
@RestController
@RequestMapping("/api/v1/http-writer-sink")
public class HttpWriterSinkController {

    private static final Logger log = LoggerFactory.getLogger(HttpWriterSinkController.class);
    private static final int MAX_RECORDS = 50;
    private static final AtomicLong SEQUENCE = new AtomicLong(0L);
    private static final ConcurrentLinkedDeque<Map<String, Object>> RECEIVED = new ConcurrentLinkedDeque<Map<String, Object>>();

    @Operation(summary = "Receive and print HTTP writer payload")
    @PostMapping("/print")
    public Result<Map<String, Object>> print(@RequestHeader Map<String, String> headers,
                                             @RequestParam Map<String, String> params,
                                             @RequestBody(required = false) String body,
                                             HttpServletRequest request) {
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("sequence", SEQUENCE.incrementAndGet());
        record.put("receivedAt", LocalDateTime.now().toString());
        record.put("method", request.getMethod());
        record.put("requestUri", request.getRequestURI());
        record.put("queryString", request.getQueryString());
        record.put("remoteAddr", request.getRemoteAddr());
        record.put("headers", orderedHeaders(request, headers));
        record.put("params", new LinkedHashMap<String, String>(params));
        record.put("body", body == null ? "" : body);
        RECEIVED.addFirst(record);
        while (RECEIVED.size() > MAX_RECORDS) {
            RECEIVED.pollLast();
        }
        log.info("HTTP writer sink received: {}", record);

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("received", Boolean.TRUE);
        response.put("sequence", record.get("sequence"));
        return Result.success(response);
    }

    @Operation(summary = "List recently received HTTP writer payloads")
    @GetMapping("/received")
    public Result<List<Map<String, Object>>> received() {
        return Result.success(new ArrayList<Map<String, Object>>(RECEIVED));
    }

    @Operation(summary = "Clear recently received HTTP writer payloads")
    @PostMapping("/received/clear")
    public Result<Void> clear() {
        RECEIVED.clear();
        return Result.success(null);
    }

    private Map<String, String> orderedHeaders(HttpServletRequest request, Map<String, String> fallbackHeaders) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        List<String> names = new ArrayList<String>();
        Enumeration<String> enumeration = request.getHeaderNames();
        while (enumeration != null && enumeration.hasMoreElements()) {
            names.add(enumeration.nextElement());
        }
        Collections.sort(names);
        for (String name : names) {
            result.put(name, request.getHeader(name));
        }
        if (result.isEmpty() && fallbackHeaders != null) {
            result.putAll(fallbackHeaders);
        }
        return result;
    }
}
