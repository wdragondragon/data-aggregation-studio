package com.jdragon.studio.server.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.DataIngestionInvokeResult;
import com.jdragon.studio.infra.service.DataIngestionService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/openapi/data-ingestion-services")
public class OpenDataIngestionServiceController {

    private static final String TOKEN_HEADER = "X-Data-Ingestion-Token";

    private final DataIngestionService dataIngestionService;
    private final ObjectMapper objectMapper;

    public OpenDataIngestionServiceController(DataIngestionService dataIngestionService, ObjectMapper objectMapper) {
        this.dataIngestionService = dataIngestionService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{serviceCode}/{serviceKey}")
    public void invoke(@PathVariable("serviceCode") String serviceCode,
                       @PathVariable("serviceKey") String serviceKey,
                       @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        try {
            Object body = readJsonBody(request);
            DataIngestionInvokeResult result = dataIngestionService.invoke(serviceCode,
                    serviceKey,
                    token,
                    headers(request),
                    queryParams(request),
                    formParams(request),
                    body,
                    request.getMethod(),
                    resolveClientIp(request),
                    request.getHeader("User-Agent"));
            writeJson(response, 200, result);
        } catch (StudioException ex) {
            int status = StudioErrorCode.UNAUTHORIZED.equals(ex.getCode()) ? 401
                    : StudioErrorCode.NOT_FOUND.equals(ex.getCode()) ? 404
                    : StudioErrorCode.INTERNAL_SERVER_ERROR.equals(ex.getCode()) ? 500 : 400;
            writeJson(response, status, Result.error(ex.getCode(), ex.getMessage()));
        } catch (Exception ex) {
            writeJson(response, 500, Result.error(StudioErrorCode.INTERNAL_SERVER_ERROR, ex.getMessage()));
        }
    }

    private Object readJsonBody(HttpServletRequest request) throws IOException {
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase().contains("application/json")) {
            return noJsonBody();
        }
        return objectMapper.readValue(request.getInputStream(), Object.class);
    }

    private Object noJsonBody() {
        return java.util.Optional.empty().orElse(null);
    }

    private void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private Map<String, Object> headers(HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            result.put(name, request.getHeader(name));
        }
        return result;
    }

    private Map<String, Object> queryParams(HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        String queryString = request.getQueryString();
        if (queryString == null || queryString.trim().isEmpty()) {
            return result;
        }
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int index = pair.indexOf('=');
            String key = index >= 0 ? pair.substring(0, index) : pair;
            String value = index >= 0 ? pair.substring(index + 1) : "";
            result.put(decode(key), decode(value));
        }
        return result;
    }

    private Map<String, Object> formParams(HttpServletRequest request) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase().contains("application/x-www-form-urlencoded")) {
            return result;
        }
        Map<String, String[]> parameters = request.getParameterMap();
        for (Map.Entry<String, String[]> entry : parameters.entrySet()) {
            String[] values = entry.getValue();
            if (values == null || values.length == 0) {
                result.put(entry.getKey(), null);
            } else if (values.length == 1) {
                result.put(entry.getKey(), values[0]);
            } else {
                result.put(entry.getKey(), values);
            }
        }
        return result;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.trim().isEmpty()) {
            int commaIndex = forwardedFor.indexOf(',');
            return commaIndex >= 0 ? forwardedFor.substring(0, commaIndex).trim() : forwardedFor.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.trim().isEmpty()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
