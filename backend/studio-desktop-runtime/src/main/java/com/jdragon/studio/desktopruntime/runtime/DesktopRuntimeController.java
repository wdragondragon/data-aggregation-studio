package com.jdragon.studio.desktopruntime.runtime;

import com.jdragon.studio.dto.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "DesktopRuntime", description = "Desktop runtime APIs")
@RestController
@RequestMapping("/api/v1/runtime")
public class DesktopRuntimeController {

    @Operation(summary = "Get desktop runtime mode")
    @GetMapping("/mode")
    public Result<Map<String, Object>> mode() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("mode", "DESKTOP");
        payload.put("syncStrategy", "IMPORT_EXPORT");
        payload.put("offlineExecution", true);
        return Result.success(payload);
    }
}
