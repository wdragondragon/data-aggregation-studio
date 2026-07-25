package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.server.web.service.RuntimeInvocationRouter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/openapi/protocol-conversions")
public class OpenProtocolConversionController {

    private final RuntimeInvocationRouter runtimeInvocationRouter;

    public OpenProtocolConversionController(RuntimeInvocationRouter runtimeInvocationRouter) {
        this.runtimeInvocationRouter = runtimeInvocationRouter;
    }

    @RequestMapping(value = "/{serviceCode}/{serviceKey}",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH})
    public void invoke(@PathVariable("serviceCode") String serviceCode,
                       @PathVariable("serviceKey") String serviceKey,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        runtimeInvocationRouter.routeIfRemote(
                "protocol-conversions", serviceCode, serviceKey, "REST", request, response);
    }
}
