package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.WebServiceSoapVersion;
import com.jdragon.studio.infra.service.DataIngestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/openapi/ws/data-ingestion-services")
public class OpenWebServiceDataIngestionController {

    private static final String TOKEN_HEADER = "X-Data-Ingestion-Token";

    private final DataIngestionService dataIngestionService;

    public OpenWebServiceDataIngestionController(DataIngestionService dataIngestionService) {
        this.dataIngestionService = dataIngestionService;
    }

    @GetMapping(value = "/{serviceCode}/{serviceKey}", params = "wsdl")
    public void wsdl(@PathVariable("serviceCode") String serviceCode,
                     @PathVariable("serviceKey") String serviceKey,
                     HttpServletRequest request,
                     HttpServletResponse response) throws IOException {
        try {
            writeXml(response, 200, dataIngestionService.webServiceWsdl(serviceCode, serviceKey, request.getRequestURL().toString()));
        } catch (StudioException ex) {
            writeXml(response, statusFor(ex), dataIngestionService.webServiceFault(WebServiceSoapVersion.SOAP_11, ex.getCode(), ex.getMessage()));
        }
    }

    @PostMapping("/{serviceCode}/{serviceKey}")
    public void invoke(@PathVariable("serviceCode") String serviceCode,
                       @PathVariable("serviceKey") String serviceKey,
                       @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        try {
            String result = dataIngestionService.invokeWebService(serviceCode,
                    serviceKey,
                    token,
                    headers(request),
                    readBody(request),
                    resolveClientIp(request),
                    request.getHeader("User-Agent"));
            writeXml(response, 200, result);
        } catch (StudioException ex) {
            writeXml(response, statusFor(ex), dataIngestionService.webServiceFault(WebServiceSoapVersion.SOAP_11, ex.getCode(), ex.getMessage()));
        } catch (Exception ex) {
            writeXml(response, 500,
                    dataIngestionService.webServiceFault(WebServiceSoapVersion.SOAP_11, StudioErrorCode.INTERNAL_SERVER_ERROR, ex.getMessage()));
        }
    }

    private String readBody(HttpServletRequest request) throws IOException {
        return new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void writeXml(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/xml;charset=UTF-8");
        response.getWriter().write(body == null ? "" : body);
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
