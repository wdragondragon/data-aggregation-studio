package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.ArtifactStoreView;
import com.jdragon.studio.dto.model.request.ArtifactStoreSaveRequest;
import com.jdragon.studio.infra.service.ArtifactStoreService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@Tag(name = "Artifact Stores", description = "Tenant artifact repository control plane")
@RestController
@RequestMapping("/api/v1/artifact-stores")
public class ArtifactStoreController {
    private final ArtifactStoreService service;
    public ArtifactStoreController(ArtifactStoreService service) { this.service = service; }
    @GetMapping public Result<List<ArtifactStoreView>> list(@RequestParam(value = "enabledOnly", defaultValue = "false") boolean enabledOnly) {
        return Result.success(service.list(enabledOnly));
    }
    @GetMapping("/{id}") public Result<ArtifactStoreView> get(@PathVariable("id") Long id) { return Result.success(service.get(id)); }
    @PostMapping public Result<ArtifactStoreView> save(@RequestBody ArtifactStoreSaveRequest request) { return Result.success(service.save(request)); }
    @PostMapping("/{id}/test") public Result<Map<String, Object>> test(@PathVariable("id") Long id) { return Result.success(service.test(id)); }
    @PostMapping("/{id}/enable") public Result<ArtifactStoreView> enable(@PathVariable("id") Long id) { return Result.success(service.setEnabled(id, true)); }
    @PostMapping("/{id}/disable") public Result<ArtifactStoreView> disable(@PathVariable("id") Long id) { return Result.success(service.setEnabled(id, false)); }
    @DeleteMapping("/{id}") public Result<Void> delete(@PathVariable("id") Long id) { service.delete(id); return Result.success(null); }
}
