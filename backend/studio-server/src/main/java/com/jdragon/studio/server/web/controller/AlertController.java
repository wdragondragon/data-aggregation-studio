package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.AlertChannelView;
import com.jdragon.studio.dto.model.AlertDeliveryView;
import com.jdragon.studio.dto.model.AlertEventView;
import com.jdragon.studio.dto.model.AlertIncidentView;
import com.jdragon.studio.dto.model.AlertOptionsView;
import com.jdragon.studio.dto.model.AlertRuleView;
import com.jdragon.studio.dto.model.AlertSelectOptionView;
import com.jdragon.studio.dto.model.AlertSummaryView;
import com.jdragon.studio.dto.model.AlertTenantProjectSummaryView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.AlertChannelQueryRequest;
import com.jdragon.studio.dto.model.request.AlertChannelSaveRequest;
import com.jdragon.studio.dto.model.request.AlertDeliveryQueryRequest;
import com.jdragon.studio.dto.model.request.AlertIncidentActionRequest;
import com.jdragon.studio.dto.model.request.AlertIncidentQueryRequest;
import com.jdragon.studio.dto.model.request.AlertRuleQueryRequest;
import com.jdragon.studio.dto.model.request.AlertRuleSaveRequest;
import com.jdragon.studio.dto.model.request.AlertTenantSummaryQueryRequest;
import com.jdragon.studio.infra.service.AlertChannelService;
import com.jdragon.studio.infra.service.AlertDeliveryService;
import com.jdragon.studio.infra.service.AlertIncidentService;
import com.jdragon.studio.infra.service.AlertRuleService;
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

@Tag(name = "Alert Center", description = "Unified alert rules, incidents, channels and deliveries")
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertRuleService alertRuleService;
    private final AlertIncidentService alertIncidentService;
    private final AlertChannelService alertChannelService;
    private final AlertDeliveryService alertDeliveryService;

    public AlertController(AlertRuleService alertRuleService,
                           AlertIncidentService alertIncidentService,
                           AlertChannelService alertChannelService,
                           AlertDeliveryService alertDeliveryService) {
        this.alertRuleService = alertRuleService;
        this.alertIncidentService = alertIncidentService;
        this.alertChannelService = alertChannelService;
        this.alertDeliveryService = alertDeliveryService;
    }

    @Operation(summary = "Get alert rule metadata and permissions")
    @GetMapping("/options")
    public Result<AlertOptionsView> options() {
        return Result.success(alertRuleService.options());
    }

    @Operation(summary = "Query alert subjects")
    @GetMapping("/subjects")
    public Result<PageView<AlertSelectOptionView>> subjects(@RequestParam("subjectType") String subjectType,
                                                            @RequestParam(value = "keyword", required = false) String keyword,
                                                            @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return Result.success(alertRuleService.subjectOptions(subjectType, keyword, pageNo, pageSize));
    }

    @Operation(summary = "Query alert recipient options")
    @GetMapping("/recipient-options")
    public Result<PageView<AlertSelectOptionView>> recipients(@RequestParam(value = "keyword", required = false) String keyword,
                                                              @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                              @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return Result.success(alertRuleService.recipientOptions(keyword, pageNo, pageSize));
    }

    @Operation(summary = "Get current project alert summary")
    @GetMapping("/summary")
    public Result<AlertSummaryView> summary() {
        return Result.success(alertIncidentService.summary());
    }

    @Operation(summary = "Query tenant project alert summaries")
    @PostMapping("/tenant-summary/query")
    public Result<PageView<AlertTenantProjectSummaryView>> tenantSummary(@RequestBody(required = false) AlertTenantSummaryQueryRequest request) {
        return Result.success(alertIncidentService.tenantSummary(request));
    }

    @Operation(summary = "Query alert rules")
    @PostMapping("/rules/query")
    public Result<PageView<AlertRuleView>> queryRules(@RequestBody(required = false) AlertRuleQueryRequest request) {
        return Result.success(alertRuleService.query(request));
    }

    @Operation(summary = "Get alert rule")
    @GetMapping("/rules/{id}")
    public Result<AlertRuleView> getRule(@PathVariable("id") Long id) {
        return Result.success(alertRuleService.get(id));
    }

    @Operation(summary = "Create or update alert rule")
    @PostMapping("/rules")
    public Result<AlertRuleView> saveRule(@Valid @RequestBody AlertRuleSaveRequest request) {
        return Result.success(alertRuleService.save(request));
    }

    @Operation(summary = "Delete alert rule")
    @DeleteMapping("/rules/{id}")
    public Result<Void> deleteRule(@PathVariable("id") Long id) {
        alertRuleService.delete(id);
        return Result.success(null);
    }

    @Operation(summary = "Enable alert rule")
    @PostMapping("/rules/{id}/enable")
    public Result<AlertRuleView> enableRule(@PathVariable("id") Long id) {
        return Result.success(alertRuleService.enable(id));
    }

    @Operation(summary = "Disable alert rule")
    @PostMapping("/rules/{id}/disable")
    public Result<AlertRuleView> disableRule(@PathVariable("id") Long id) {
        return Result.success(alertRuleService.disable(id));
    }

    @Operation(summary = "Test alert rule")
    @PostMapping("/rules/{id}/test")
    public Result<AlertEventView> testRule(@PathVariable("id") Long id) {
        return Result.success(alertIncidentService.testRule(id));
    }

    @Operation(summary = "Query alert incidents")
    @PostMapping("/incidents/query")
    public Result<PageView<AlertIncidentView>> queryIncidents(@RequestBody(required = false) AlertIncidentQueryRequest request) {
        return Result.success(alertIncidentService.query(request));
    }

    @Operation(summary = "Get alert incident detail")
    @GetMapping("/incidents/{id}")
    public Result<AlertIncidentView> getIncident(@PathVariable("id") Long id) {
        return Result.success(alertIncidentService.get(id));
    }

    @Operation(summary = "Query alert incident events")
    @GetMapping("/incidents/{id}/events")
    public Result<PageView<AlertEventView>> incidentEvents(@PathVariable("id") Long id,
                                                           @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                           @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return Result.success(alertIncidentService.events(id, pageNo, pageSize));
    }

    @Operation(summary = "Acknowledge alert incident")
    @PostMapping("/incidents/{id}/acknowledge")
    public Result<AlertIncidentView> acknowledge(@PathVariable("id") Long id,
                                                 @RequestBody(required = false) AlertIncidentActionRequest request) {
        return Result.success(alertIncidentService.acknowledge(id, request));
    }

    @Operation(summary = "Close alert incident")
    @PostMapping("/incidents/{id}/close")
    public Result<AlertIncidentView> close(@PathVariable("id") Long id,
                                           @RequestBody(required = false) AlertIncidentActionRequest request) {
        return Result.success(alertIncidentService.close(id, request));
    }

    @Operation(summary = "Query alert channels")
    @PostMapping("/channels/query")
    public Result<PageView<AlertChannelView>> queryChannels(@RequestBody(required = false) AlertChannelQueryRequest request) {
        return Result.success(alertChannelService.query(request));
    }

    @Operation(summary = "Get alert channel")
    @GetMapping("/channels/{id}")
    public Result<AlertChannelView> getChannel(@PathVariable("id") Long id) {
        return Result.success(alertChannelService.get(id));
    }

    @Operation(summary = "Create or update alert channel")
    @PostMapping("/channels")
    public Result<AlertChannelView> saveChannel(@Valid @RequestBody AlertChannelSaveRequest request) {
        return Result.success(alertChannelService.save(request));
    }

    @Operation(summary = "Delete alert channel")
    @DeleteMapping("/channels/{id}")
    public Result<Void> deleteChannel(@PathVariable("id") Long id) {
        alertChannelService.delete(id);
        return Result.success(null);
    }

    @Operation(summary = "Enable alert channel")
    @PostMapping("/channels/{id}/enable")
    public Result<AlertChannelView> enableChannel(@PathVariable("id") Long id) {
        return Result.success(alertChannelService.enable(id));
    }

    @Operation(summary = "Disable alert channel")
    @PostMapping("/channels/{id}/disable")
    public Result<AlertChannelView> disableChannel(@PathVariable("id") Long id) {
        return Result.success(alertChannelService.disable(id));
    }

    @Operation(summary = "Test alert channel")
    @PostMapping("/channels/{id}/test")
    public Result<AlertEventView> testChannel(@PathVariable("id") Long id) {
        return Result.success(alertIncidentService.testChannel(id));
    }

    @Operation(summary = "Query alert deliveries")
    @PostMapping("/deliveries/query")
    public Result<PageView<AlertDeliveryView>> queryDeliveries(@RequestBody(required = false) AlertDeliveryQueryRequest request) {
        return Result.success(alertDeliveryService.query(request));
    }

    @Operation(summary = "Retry alert delivery")
    @PostMapping("/deliveries/{id}/retry")
    public Result<AlertDeliveryView> retryDelivery(@PathVariable("id") Long id) {
        return Result.success(alertDeliveryService.retry(id));
    }
}
