package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.ProtocolConversionDebugResult;
import com.jdragon.studio.dto.model.ProtocolConversionServiceListView;
import com.jdragon.studio.dto.model.ProtocolConversionServiceView;
import com.jdragon.studio.dto.model.ProtocolConversionSubscriptionView;
import com.jdragon.studio.dto.model.request.DataServiceSubscriptionCreateRequest;
import com.jdragon.studio.dto.model.request.ProtocolConversionDebugRequest;
import com.jdragon.studio.dto.model.request.ProtocolConversionServiceSaveRequest;
import com.jdragon.studio.infra.service.ProtocolConversionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Protocol Conversion Services", description = "HTTP/WebService protocol conversion APIs")
@RestController
@RequestMapping("/api/v1/protocol-conversions")
public class ProtocolConversionServiceController {

    private final ProtocolConversionService protocolConversionService;

    public ProtocolConversionServiceController(ProtocolConversionService protocolConversionService) {
        this.protocolConversionService = protocolConversionService;
    }

    @Operation(summary = "List protocol conversion services")
    @GetMapping
    public Result<PageView<ProtocolConversionServiceListView>> list(@RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                                    @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                                    @RequestParam(value = "keyword", required = false) String keyword,
                                                                    @RequestParam(value = "status", required = false) String status) {
        return Result.success(protocolConversionService.list(pageNo, pageSize, keyword, status));
    }

    @Operation(summary = "Get protocol conversion service detail")
    @GetMapping("/{id}")
    public Result<ProtocolConversionServiceView> get(@PathVariable("id") Long id) {
        return Result.success(protocolConversionService.get(id));
    }

    @Operation(summary = "Create or update protocol conversion service")
    @PostMapping
    public Result<ProtocolConversionServiceView> save(@Valid @RequestBody ProtocolConversionServiceSaveRequest request) {
        return Result.success(protocolConversionService.save(request));
    }

    @Operation(summary = "Delete protocol conversion service")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        protocolConversionService.delete(id);
        return Result.success(null);
    }

    @Operation(summary = "Publish protocol conversion service")
    @PostMapping("/{id}/publish")
    public Result<ProtocolConversionServiceView> publish(@PathVariable("id") Long id) {
        return Result.success(protocolConversionService.publish(id));
    }

    @Operation(summary = "Publish protocol conversion service and return table row summary")
    @PostMapping("/{id}/publish-summary")
    public Result<ProtocolConversionServiceListView> publishSummary(@PathVariable("id") Long id) {
        return Result.success(protocolConversionService.publishSummary(id));
    }

    @Operation(summary = "Offline protocol conversion service")
    @PostMapping("/{id}/offline")
    public Result<ProtocolConversionServiceView> offline(@PathVariable("id") Long id) {
        return Result.success(protocolConversionService.offline(id));
    }

    @Operation(summary = "Offline protocol conversion service and return table row summary")
    @PostMapping("/{id}/offline-summary")
    public Result<ProtocolConversionServiceListView> offlineSummary(@PathVariable("id") Long id) {
        return Result.success(protocolConversionService.offlineSummary(id));
    }

    @Operation(summary = "Debug protocol conversion service")
    @PostMapping("/{id}/debug")
    public Result<ProtocolConversionDebugResult> debug(@PathVariable("id") Long id,
                                                       @RequestBody(required = false) ProtocolConversionDebugRequest request) {
        return Result.success(protocolConversionService.debug(id, request));
    }

    @Operation(summary = "List protocol conversion service subscriptions")
    @GetMapping("/{id}/subscriptions")
    public Result<List<ProtocolConversionSubscriptionView>> subscriptions(@PathVariable("id") Long id) {
        return Result.success(protocolConversionService.listSubscriptions(id));
    }

    @Operation(summary = "Create protocol conversion service subscription token")
    @PostMapping("/{id}/subscriptions")
    public Result<ProtocolConversionSubscriptionView> createSubscription(@PathVariable("id") Long id,
                                                                        @Valid @RequestBody DataServiceSubscriptionCreateRequest request) {
        return Result.success(protocolConversionService.createSubscription(id, request));
    }

    @Operation(summary = "Rotate protocol conversion service subscription token")
    @PostMapping("/{id}/subscriptions/{subscriptionId}/rotate")
    public Result<ProtocolConversionSubscriptionView> rotateSubscription(@PathVariable("id") Long id,
                                                                        @PathVariable("subscriptionId") Long subscriptionId) {
        return Result.success(protocolConversionService.rotateSubscription(id, subscriptionId));
    }

    @Operation(summary = "Disable protocol conversion service subscription token")
    @PostMapping("/{id}/subscriptions/{subscriptionId}/disable")
    public Result<ProtocolConversionSubscriptionView> disableSubscription(@PathVariable("id") Long id,
                                                                         @PathVariable("subscriptionId") Long subscriptionId) {
        return Result.success(protocolConversionService.disableSubscription(id, subscriptionId));
    }

    @Operation(summary = "Enable protocol conversion service subscription token")
    @PostMapping("/{id}/subscriptions/{subscriptionId}/enable")
    public Result<ProtocolConversionSubscriptionView> enableSubscription(@PathVariable("id") Long id,
                                                                        @PathVariable("subscriptionId") Long subscriptionId) {
        return Result.success(protocolConversionService.enableSubscription(id, subscriptionId));
    }
}
