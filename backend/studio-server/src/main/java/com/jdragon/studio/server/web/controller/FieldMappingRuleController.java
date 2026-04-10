package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.FieldMappingRuleView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.FieldMappingRuleSaveRequest;
import com.jdragon.studio.infra.service.FieldMappingRuleService;
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

import javax.validation.Valid;
import java.util.List;

@Tag(name = "Field Mapping Rules", description = "System-level field mapping rule management APIs")
@RestController
@RequestMapping("/api/v1/field-mapping-rules")
public class FieldMappingRuleController {

    private final FieldMappingRuleService fieldMappingRuleService;

    public FieldMappingRuleController(FieldMappingRuleService fieldMappingRuleService) {
        this.fieldMappingRuleService = fieldMappingRuleService;
    }

    @Operation(summary = "List field mapping rules")
    @GetMapping
    public Result<PageView<FieldMappingRuleView>> list(@RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                       @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                                       @RequestParam(value = "keyword", required = false) String keyword,
                                                       @RequestParam(value = "mappingType", required = false) String mappingType,
                                                       @RequestParam(value = "enabled", required = false) Boolean enabled) {
        return Result.success(fieldMappingRuleService.list(pageNo, pageSize, keyword, mappingType, enabled));
    }

    @Operation(summary = "List enabled field mapping rule options")
    @GetMapping("/options")
    public Result<List<FieldMappingRuleView>> options(@RequestParam(value = "mappingType", required = false) String mappingType) {
        return Result.success(fieldMappingRuleService.options(mappingType));
    }

    @Operation(summary = "Get field mapping rule detail")
    @GetMapping("/{id}")
    public Result<FieldMappingRuleView> get(@PathVariable("id") Long id) {
        return Result.success(fieldMappingRuleService.get(id));
    }

    @Operation(summary = "Create or update field mapping rule")
    @PostMapping
    public Result<FieldMappingRuleView> save(@Valid @RequestBody FieldMappingRuleSaveRequest request) {
        return Result.success(fieldMappingRuleService.save(request));
    }

    @Operation(summary = "Delete field mapping rule")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        fieldMappingRuleService.delete(id);
        return Result.success(null);
    }
}
