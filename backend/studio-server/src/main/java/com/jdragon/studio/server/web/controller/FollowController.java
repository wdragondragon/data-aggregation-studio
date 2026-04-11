package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.FollowStatusView;
import com.jdragon.studio.dto.model.request.FollowRequest;
import com.jdragon.studio.infra.service.FollowSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Follows", description = "Follow subscription APIs")
@RestController
@RequestMapping("/api/v1/follows")
public class FollowController {

    private final FollowSubscriptionService followSubscriptionService;

    public FollowController(FollowSubscriptionService followSubscriptionService) {
        this.followSubscriptionService = followSubscriptionService;
    }

    @Operation(summary = "Get follow status")
    @GetMapping("/status")
    public Result<FollowStatusView> status(@RequestParam("targetType") String targetType,
                                           @RequestParam("targetId") Long targetId) {
        return Result.success(followSubscriptionService.status(targetType, targetId));
    }

    @Operation(summary = "Follow target")
    @PostMapping
    public Result<FollowStatusView> follow(@RequestBody FollowRequest request) {
        return Result.success(followSubscriptionService.follow(request));
    }

    @Operation(summary = "Unfollow target")
    @DeleteMapping
    public Result<Void> unfollow(@RequestParam("targetType") String targetType,
                                 @RequestParam("targetId") Long targetId) {
        followSubscriptionService.unfollow(targetType, targetId);
        return Result.success(null);
    }
}
