package com.jdragon.studio.server.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.enums.ProtocolConversionProtocol;
import com.jdragon.studio.dto.model.ProtocolConversionInvokeResult;
import com.jdragon.studio.infra.service.ProtocolConversionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/openapi/protocol-conversions")
public class OpenProtocolConversionController {

    private static final String TOKEN_HEADER = "X-Protocol-Conversion-Token";

    private final ProtocolConversionService protocolConversionService;
    private final ObjectMapper objectMapper;

    public OpenProtocolConversionController(ProtocolConversionService protocolConversionService,
                                            ObjectMapper objectMapper) {
        this.protocolConversionService = protocolConversionService;
        this.objectMapper = objectMapper;
    }

    @RequestMapping(value = "/{serviceCode}/{serviceKey}", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH})
    public void invoke(@PathVariable("serviceCode") String serviceCode,
                       @PathVariable("serviceKey") String serviceKey,
                       @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                       @RequestParam(required = false) Map<String, Object> query,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        ProtocolConversionProtocol sourceProtocol = null;
        try {
            sourceProtocol = protocolConversionService.openSourceProtocol(serviceCode, serviceKey);
            ProtocolConversionInvokeResult result = protocolConversionService.invoke(serviceCode,
                    serviceKey,
                    token,
                    headers(request),
                    query == null ? queryParams(request) : query,
                    formParams(request),
                    readBody(request),
                    request.getMethod(),
                    resolveClientIp(request),
                    request.getHeader("User-Agent"));
            writeSuccess(response, sourceProtocol, result.getResponseBody());
        } catch (StudioException ex) {
            writeError(response, sourceProtocol, statusFor(ex), ex.getCode(), ex.getMessage());
        } catch (Exception ex) {
            writeError(response, sourceProtocol, 500, StudioErrorCode.INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    private String readBody(HttpServletRequest request) throws IOException {
        return new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private void writeXml(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/xml;charset=UTF-8");
        response.getWriter().write(body == null ? "" : body);
    }

    private void writeSuccess(HttpServletResponse response,
                              ProtocolConversionProtocol sourceProtocol,
                              Object responseBody) throws IOException {
        if (sourceProtocol == ProtocolConversionProtocol.HTTP_XML) {
            writeXml(response, 200, protocolConversionService.httpXmlResponseBody(responseBody));
            return;
        }
        writeJson(response, 200, responseBody);
    }

    private void writeError(HttpServletResponse response,
                            ProtocolConversionProtocol sourceProtocol,
                            int status,
                            String code,
                            String message) throws IOException {
        if (sourceProtocol == ProtocolConversionProtocol.HTTP_XML) {
            Map<String, Object> error = new LinkedHashMap<String, Object>();
            error.put("code", code);
            error.put("message", message);
            writeXml(response, status, protocolConversionService.httpXmlResponseBody(error));
            return;
        }
        writeJson(response, status, Result.error(code, message));
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

    private int statusFor(StudioException ex) {
        if (StudioErrorCode.UNAUTHORIZED.equals(ex.getCode())) {
            return 401;
        }
        if (StudioErrorCode.NOT_FOUND.equals(ex.getCode())) {
            return 404;
        }
        if (StudioErrorCode.INTERNAL_SERVER_ERROR.equals(ex.getCode())) {
            return 500;
        }
        return 400;
    }
}
