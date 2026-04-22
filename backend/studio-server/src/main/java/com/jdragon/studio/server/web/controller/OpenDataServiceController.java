package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.infra.service.DataServiceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/openapi/data-services")
public class OpenDataServiceController {

    private static final String TOKEN_HEADER = "X-Data-Service-Token";

    private final DataServiceService dataServiceService;
    private final ObjectMapper objectMapper;

    public OpenDataServiceController(DataServiceService dataServiceService, ObjectMapper objectMapper) {
        this.dataServiceService = dataServiceService;
        this.objectMapper = objectMapper;
    }

    @RequestMapping(value = "/{serviceCode}/{serviceKey}", method = {RequestMethod.GET, RequestMethod.POST})
    public void invoke(@PathVariable("serviceCode") String serviceCode,
                       @PathVariable("serviceKey") String serviceKey,
                       @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                       @RequestParam Map<String, Object> query,
                       @RequestBody(required = false) Map<String, Object> body,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        try {
            Map<String, Object> result = dataServiceService.invoke(serviceCode,
                    serviceKey,
                    token,
                    headers(request),
                    query == null ? new LinkedHashMap<String, Object>() : query,
                    body == null ? new LinkedHashMap<String, Object>() : body,
                    request.getMethod(),
                    resolveClientIp(request),
                    request.getHeader("User-Agent"));
            writeJson(response, 200, Result.success(result));
        } catch (StudioException ex) {
            int status = StudioErrorCode.UNAUTHORIZED.equals(ex.getCode()) ? 401
                    : StudioErrorCode.NOT_FOUND.equals(ex.getCode()) ? 404 : 400;
            writeJson(response, status, Result.error(ex.getCode(), ex.getMessage()));
        } catch (Exception ex) {
            writeJson(response, 500, Result.error(StudioErrorCode.INTERNAL_SERVER_ERROR, ex.getMessage()));
        }
    }

    private void writeJson(HttpServletResponse response, int status, Result<?> body) throws IOException {
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

