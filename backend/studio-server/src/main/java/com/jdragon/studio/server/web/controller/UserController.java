package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.StudioUserListView;
import com.jdragon.studio.dto.model.StudioUserOptionView;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.service.UserManagementService;
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

import java.util.List;

@Tag(name = "Users", description = "User management APIs")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserManagementService userManagementService;

    public UserController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @Operation(summary = "List users")
    @GetMapping
    public Result<List<StudioUserListView>> list() {
        return Result.success(userManagementService.list());
    }

    @Operation(summary = "List lightweight user options")
    @GetMapping("/options")
    public Result<List<StudioUserOptionView>> listOptions() {
        return Result.success(userManagementService.listOptions());
    }

    @Operation(summary = "List users by page")
    @GetMapping("/page")
    public Result<PageView<StudioUserListView>> listPage(@RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                         @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return Result.success(userManagementService.listPage(pageNo, pageSize));
    }

    @Operation(summary = "Create or update user")
    @PostMapping
    public Result<StudioUserEntity> save(@RequestBody StudioUserEntity entity) {
        return Result.success(userManagementService.save(entity));
    }

    @Operation(summary = "Delete user")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        userManagementService.delete(id);
        return Result.success(null);
    }
}
