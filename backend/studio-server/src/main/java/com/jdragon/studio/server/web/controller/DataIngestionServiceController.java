package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.DataIngestionInvokeResult;
import com.jdragon.studio.dto.model.DataIngestionResolveFieldsView;
import com.jdragon.studio.dto.model.DataIngestionServiceListView;
import com.jdragon.studio.dto.model.DataIngestionServiceView;
import com.jdragon.studio.dto.model.DataIngestionSubscriptionView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.WebServiceDebugResult;
import com.jdragon.studio.dto.model.WebServicePreviewView;
import com.jdragon.studio.dto.model.request.DataIngestionDebugRequest;
import com.jdragon.studio.dto.model.request.DataIngestionResolveFieldsRequest;
import com.jdragon.studio.dto.model.request.DataIngestionServiceSaveRequest;
import com.jdragon.studio.dto.model.request.DataServiceSubscriptionCreateRequest;
import com.jdragon.studio.dto.model.request.WebServiceDebugRequest;
import com.jdragon.studio.infra.service.DataIngestionService;
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

@Tag(name = "Data Ingestion Services", description = "Write-side data ingestion APIs")
@RestController
@RequestMapping("/api/v1/data-ingestion-services")
public class DataIngestionServiceController {

    private final DataIngestionService dataIngestionService;

    public DataIngestionServiceController(DataIngestionService dataIngestionService) {
        this.dataIngestionService = dataIngestionService;
    }

    @Operation(summary = "List data ingestion services")
    @GetMapping
    public Result<PageView<DataIngestionServiceListView>> list(@RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                               @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                               @RequestParam(value = "keyword", required = false) String keyword,
                                                               @RequestParam(value = "status", required = false) String status,
                                                               @RequestParam(value = "targetType", required = false) String targetType) {
        return Result.success(dataIngestionService.list(pageNo, pageSize, keyword, status, targetType));
    }

    @Operation(summary = "Get data ingestion service detail")
    @GetMapping("/{id}")
    public Result<DataIngestionServiceView> get(@PathVariable("id") Long id) {
        return Result.success(dataIngestionService.get(id));
    }

    @Operation(summary = "Create or update data ingestion service")
    @PostMapping
    public Result<DataIngestionServiceView> save(@Valid @RequestBody DataIngestionServiceSaveRequest request) {
        return Result.success(dataIngestionService.save(request));
    }

    @Operation(summary = "Delete data ingestion service")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        dataIngestionService.delete(id);
        return Result.success(null);
    }

    @Operation(summary = "Publish data ingestion service")
    @PostMapping("/{id}/publish")
    public Result<DataIngestionServiceView> publish(@PathVariable("id") Long id) {
        return Result.success(dataIngestionService.publish(id));
    }

    @Operation(summary = "Offline data ingestion service")
    @PostMapping("/{id}/offline")
    public Result<DataIngestionServiceView> offline(@PathVariable("id") Long id) {
        return Result.success(dataIngestionService.offline(id));
    }

    @Operation(summary = "Resolve target fields")
    @PostMapping("/resolve-fields")
    public Result<DataIngestionResolveFieldsView> resolveFields(@RequestBody DataIngestionResolveFieldsRequest request) {
        return Result.success(dataIngestionService.resolveFields(request));
    }

    @Operation(summary = "Debug data ingestion service")
    @PostMapping("/{id}/debug")
    public Result<DataIngestionInvokeResult> debug(@PathVariable("id") Long id,
                                                   @RequestBody(required = false) DataIngestionDebugRequest request) {
        return Result.success(dataIngestionService.debug(id, request));
    }

    @Operation(summary = "Preview data ingestion service WebService contract")
    @GetMapping("/{id}/webservice/preview")
    public Result<WebServicePreviewView> previewWebService(@PathVariable("id") Long id) {
        return Result.success(dataIngestionService.previewWebService(id));
    }

    @Operation(summary = "Debug data ingestion service WebService")
    @PostMapping("/{id}/webservice/debug")
    public Result<WebServiceDebugResult> debugWebService(@PathVariable("id") Long id,
                                                         @RequestBody(required = false) WebServiceDebugRequest request) {
        return Result.success(dataIngestionService.debugWebService(id, request));
    }

    @Operation(summary = "List data ingestion service subscriptions")
    @GetMapping("/{id}/subscriptions")
    public Result<List<DataIngestionSubscriptionView>> subscriptions(@PathVariable("id") Long id) {
        return Result.success(dataIngestionService.listSubscriptions(id));
    }

    @Operation(summary = "Create data ingestion service subscription token")
    @PostMapping("/{id}/subscriptions")
    public Result<DataIngestionSubscriptionView> createSubscription(@PathVariable("id") Long id,
                                                                    @Valid @RequestBody DataServiceSubscriptionCreateRequest request) {
        return Result.success(dataIngestionService.createSubscription(id, request));
    }

    @Operation(summary = "Rotate data ingestion service subscription token")
    @PostMapping("/{id}/subscriptions/{subscriptionId}/rotate")
    public Result<DataIngestionSubscriptionView> rotateSubscription(@PathVariable("id") Long id,
                                                                    @PathVariable("subscriptionId") Long subscriptionId) {
        return Result.success(dataIngestionService.rotateSubscription(id, subscriptionId));
    }

    @Operation(summary = "Disable data ingestion service subscription token")
    @PostMapping("/{id}/subscriptions/{subscriptionId}/disable")
    public Result<DataIngestionSubscriptionView> disableSubscription(@PathVariable("id") Long id,
                                                                     @PathVariable("subscriptionId") Long subscriptionId) {
        return Result.success(dataIngestionService.disableSubscription(id, subscriptionId));
    }

    @Operation(summary = "Enable data ingestion service subscription token")
    @PostMapping("/{id}/subscriptions/{subscriptionId}/enable")
    public Result<DataIngestionSubscriptionView> enableSubscription(@PathVariable("id") Long id,
                                                                    @PathVariable("subscriptionId") Long subscriptionId) {
        return Result.success(dataIngestionService.enableSubscription(id, subscriptionId));
    }
}
