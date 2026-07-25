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
@RequestMapping("/openapi/data-services")
public class OpenDataServiceController {

    private final RuntimeInvocationRouter runtimeInvocationRouter;

    public OpenDataServiceController(RuntimeInvocationRouter runtimeInvocationRouter) {
        this.runtimeInvocationRouter = runtimeInvocationRouter;
    }

    @RequestMapping(value = "/{serviceCode}/{serviceKey}", method = {RequestMethod.GET, RequestMethod.POST})
    public void invoke(@PathVariable("serviceCode") String serviceCode,
                       @PathVariable("serviceKey") String serviceKey,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        runtimeInvocationRouter.routeIfRemote(
                "data-services", serviceCode, serviceKey, "REST", request, response);
    }
}
