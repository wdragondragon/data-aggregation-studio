package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.server.web.service.RuntimeInvocationRouter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/openapi/data-ingestion-services")
public class OpenDataIngestionServiceController {

    private final RuntimeInvocationRouter runtimeInvocationRouter;

    public OpenDataIngestionServiceController(RuntimeInvocationRouter runtimeInvocationRouter) {
        this.runtimeInvocationRouter = runtimeInvocationRouter;
    }

    @PostMapping("/{serviceCode}/{serviceKey}")
    public void invoke(@PathVariable("serviceCode") String serviceCode,
                       @PathVariable("serviceKey") String serviceKey,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        runtimeInvocationRouter.routeIfRemote(
                "data-ingestion-services", serviceCode, serviceKey, "REST", request, response);
    }
}
