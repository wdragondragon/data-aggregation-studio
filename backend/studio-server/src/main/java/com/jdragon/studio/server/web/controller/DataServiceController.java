package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.DataServiceDefinitionView;
import com.jdragon.studio.dto.model.DataServiceResolveFieldsView;
import com.jdragon.studio.dto.model.DataServiceSubscriptionView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.DataServiceDebugRequest;
import com.jdragon.studio.dto.model.request.DataServiceResolveFieldsRequest;
import com.jdragon.studio.dto.model.request.DataServiceSaveRequest;
import com.jdragon.studio.dto.model.request.DataServiceSubscriptionCreateRequest;
import com.jdragon.studio.infra.service.DataServiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

@Tag(name = "Data Services", description = "Data service publishing APIs")
@RestController
@RequestMapping("/api/v1/data-services")
public class DataServiceController {

    private final DataServiceService dataServiceService;

    public DataServiceController(DataServiceService dataServiceService) {
        this.dataServiceService = dataServiceService;
    }

    @Operation(summary = "List data services")
    @GetMapping
    public Result<PageView<DataServiceDefinitionView>> list(@RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                            @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                            @RequestParam(value = "keyword", required = false) String keyword,
                                                            @RequestParam(value = "status", required = false) String status,
                                                            @RequestParam(value = "serviceType", required = false) String serviceType) {
        return Result.success(dataServiceService.list(pageNo, pageSize, keyword, status, serviceType));
    }

    @Operation(summary = "Get data service detail")
    @GetMapping("/{id}")
    public Result<DataServiceDefinitionView> get(@PathVariable("id") Long id) {
        return Result.success(dataServiceService.get(id));
    }

    @Operation(summary = "Create or update data service")
    @PostMapping
    public Result<DataServiceDefinitionView> save(@Valid @RequestBody DataServiceSaveRequest request) {
        return Result.success(dataServiceService.save(request));
    }

    @Operation(summary = "Delete data service")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        dataServiceService.delete(id);
        return Result.success(null);
    }

    @Operation(summary = "Publish data service")
    @PostMapping("/{id}/publish")
    public Result<DataServiceDefinitionView> publish(@PathVariable("id") Long id) {
        return Result.success(dataServiceService.publish(id));
    }

    @Operation(summary = "Offline data service")
    @PostMapping("/{id}/offline")
    public Result<DataServiceDefinitionView> offline(@PathVariable("id") Long id) {
        return Result.success(dataServiceService.offline(id));
    }

    @Operation(summary = "Resolve source fields")
    @PostMapping("/resolve-fields")
    public Result<DataServiceResolveFieldsView> resolveFields(@RequestBody DataServiceResolveFieldsRequest request) {
        return Result.success(dataServiceService.resolveFields(request));
    }

    @Operation(summary = "Debug data service")
    @PostMapping("/{id}/debug")
    public Result<Map<String, Object>> debug(@PathVariable("id") Long id,
                                             @RequestBody(required = false) DataServiceDebugRequest request) {
        return Result.success(dataServiceService.debug(id, request));
    }

    @Operation(summary = "List data service subscriptions")
    @GetMapping("/{id}/subscriptions")
    public Result<List<DataServiceSubscriptionView>> subscriptions(@PathVariable("id") Long id) {
        return Result.success(dataServiceService.listSubscriptions(id));
    }

    @Operation(summary = "Create data service subscription token")
    @PostMapping("/{id}/subscriptions")
    public Result<DataServiceSubscriptionView> createSubscription(@PathVariable("id") Long id,
                                                                  @Valid @RequestBody DataServiceSubscriptionCreateRequest request) {
        return Result.success(dataServiceService.createSubscription(id, request));
    }

    @Operation(summary = "Disable data service subscription token")
    @PostMapping("/{id}/subscriptions/{subscriptionId}/disable")
    public Result<DataServiceSubscriptionView> disableSubscription(@PathVariable("id") Long id,
                                                                   @PathVariable("subscriptionId") Long subscriptionId) {
        return Result.success(dataServiceService.disableSubscription(id, subscriptionId));
    }

    @Operation(summary = "Enable data service subscription token")
    @PostMapping("/{id}/subscriptions/{subscriptionId}/enable")
    public Result<DataServiceSubscriptionView> enableSubscription(@PathVariable("id") Long id,
                                                                  @PathVariable("subscriptionId") Long subscriptionId) {
        return Result.success(dataServiceService.enableSubscription(id, subscriptionId));
    }
}

