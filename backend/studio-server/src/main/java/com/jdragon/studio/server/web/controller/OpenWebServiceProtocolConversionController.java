package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.server.web.service.RuntimeInvocationRouter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/openapi/ws/protocol-conversions")
public class OpenWebServiceProtocolConversionController {

    private final RuntimeInvocationRouter runtimeInvocationRouter;

    public OpenWebServiceProtocolConversionController(RuntimeInvocationRouter runtimeInvocationRouter) {
        this.runtimeInvocationRouter = runtimeInvocationRouter;
    }

    @GetMapping(value = "/{serviceCode}/{serviceKey}", params = "wsdl")
    public void wsdl(@PathVariable("serviceCode") String serviceCode,
                     @PathVariable("serviceKey") String serviceKey,
                     HttpServletRequest request,
                     HttpServletResponse response) throws IOException {
        route(serviceCode, serviceKey, request, response);
    }

    @PostMapping("/{serviceCode}/{serviceKey}")
    public void invoke(@PathVariable("serviceCode") String serviceCode,
                       @PathVariable("serviceKey") String serviceKey,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        route(serviceCode, serviceKey, request, response);
    }

    private void route(String serviceCode, String serviceKey,
                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        runtimeInvocationRouter.routeIfRemote(
                "protocol-conversions", serviceCode, serviceKey, "SOAP", request, response);
    }
}
