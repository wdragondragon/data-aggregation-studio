package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.QualityRuleParseResultView;
import com.jdragon.studio.dto.model.QualityRuleListView;
import com.jdragon.studio.dto.model.QualityRuleOptionView;
import com.jdragon.studio.dto.model.QualityRuleValidationResultView;
import com.jdragon.studio.dto.model.QualityRuleView;
import com.jdragon.studio.dto.model.request.QualityRuleBatchDeleteRequest;
import com.jdragon.studio.dto.model.request.QualityRuleParseRequest;
import com.jdragon.studio.dto.model.request.QualityRuleSaveRequest;
import com.jdragon.studio.dto.model.request.QualityRuleValidateRequest;
import com.jdragon.studio.infra.service.QualityRuleService;
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

@Tag(name = "Quality Rules", description = "Quality rule management APIs")
@RestController
@RequestMapping("/api/v1/quality-rules")
public class QualityRuleController {

    private final QualityRuleService qualityRuleService;

    public QualityRuleController(QualityRuleService qualityRuleService) {
        this.qualityRuleService = qualityRuleService;
    }

    @Operation(summary = "List quality rules")
    @GetMapping
    public Result<PageView<QualityRuleListView>> list(@RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                      @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                      @RequestParam(value = "keyword", required = false) String keyword,
                                                      @RequestParam(value = "ruleDimension", required = false) String ruleDimension,
                                                      @RequestParam(value = "scopeType", required = false) String scopeType,
                                                      @RequestParam(value = "enabled", required = false) Boolean enabled) {
        return Result.success(qualityRuleService.list(pageNo, pageSize, keyword, ruleDimension, scopeType, enabled));
    }

    @Operation(summary = "List selectable quality rules")
    @GetMapping("/options")
    public Result<List<QualityRuleView>> options(@RequestParam(value = "ruleDimension", required = false) String ruleDimension,
                                                 @RequestParam(value = "granularity", required = false) String granularity,
                                                 @RequestParam(value = "datasourceType", required = false) String datasourceType,
                                                 @RequestParam(value = "enabledOnly", required = false, defaultValue = "true") Boolean enabledOnly) {
        return Result.success(qualityRuleService.options(ruleDimension, granularity, datasourceType, enabledOnly));
    }

    @Operation(summary = "List lightweight selectable quality rules")
    @GetMapping("/option-summaries")
    public Result<List<QualityRuleOptionView>> optionSummaries(@RequestParam(value = "ruleDimension", required = false) String ruleDimension,
                                                               @RequestParam(value = "granularity", required = false) String granularity,
                                                               @RequestParam(value = "datasourceType", required = false) String datasourceType,
                                                               @RequestParam(value = "enabledOnly", required = false, defaultValue = "true") Boolean enabledOnly) {
        return Result.success(qualityRuleService.optionSummaries(ruleDimension, granularity, datasourceType, enabledOnly));
    }

    @Operation(summary = "Get quality rule detail")
    @GetMapping("/{id}")
    public Result<QualityRuleView> get(@PathVariable("id") Long id) {
        return Result.success(qualityRuleService.get(id));
    }

    @Operation(summary = "Create or update quality rule")
    @PostMapping
    public Result<QualityRuleView> save(@Valid @RequestBody QualityRuleSaveRequest request) {
        return Result.success(qualityRuleService.save(request));
    }

    @Operation(summary = "Delete quality rule")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        qualityRuleService.delete(id);
        return Result.success(null);
    }

    @Operation(summary = "Batch delete quality rules")
    @PostMapping("/batch-delete")
    public Result<Void> batchDelete(@RequestBody(required = false) QualityRuleBatchDeleteRequest request) {
        qualityRuleService.batchDelete(request);
        return Result.success(null);
    }

    @Operation(summary = "Enable quality rule")
    @PostMapping("/{id}/enable")
    public Result<QualityRuleView> enable(@PathVariable("id") Long id) {
        return Result.success(qualityRuleService.enable(id));
    }

    @Operation(summary = "Disable quality rule")
    @PostMapping("/{id}/disable")
    public Result<QualityRuleView> disable(@PathVariable("id") Long id) {
        return Result.success(qualityRuleService.disable(id));
    }

    @Operation(summary = "Parse quality rule parameters")
    @PostMapping("/parse-params")
    public Result<QualityRuleParseResultView> parse(@RequestBody(required = false) QualityRuleParseRequest request) {
        return Result.success(qualityRuleService.parse(request));
    }

    @Operation(summary = "Validate quality rule logic SQL")
    @PostMapping("/validate")
    public Result<QualityRuleValidationResultView> validate(@RequestBody(required = false) QualityRuleValidateRequest request) {
        return Result.success(qualityRuleService.validate(request));
    }
}

