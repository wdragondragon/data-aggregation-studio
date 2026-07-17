package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.ElinkGroupOptionView;
import com.jdragon.studio.dto.model.ElinkUserOptionView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.infra.service.ElinkManagerOptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "eLink Options", description = "eLink Manager account and group options for alert channels")
@RestController
@RequestMapping("/api/v1/elink")
public class ElinkController {

    private final ElinkManagerOptionService optionService;

    public ElinkController(ElinkManagerOptionService optionService) {
        this.optionService = optionService;
    }

    @Operation(summary = "Query eLink account options")
    @GetMapping("/users")
    public Result<PageView<ElinkUserOptionView>> users(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return Result.success(optionService.users(keyword, pageNo, pageSize));
    }

    @Operation(summary = "Query eLink group options")
    @GetMapping("/groups")
    public Result<PageView<ElinkGroupOptionView>> groups(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return Result.success(optionService.groups(keyword, pageNo, pageSize));
    }
}
