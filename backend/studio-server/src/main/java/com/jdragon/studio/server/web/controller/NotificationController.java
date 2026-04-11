package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.NotificationSnapshotView;
import com.jdragon.studio.dto.model.NotificationView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.NotificationQueryRequest;
import com.jdragon.studio.infra.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "Notifications", description = "Inbox and notification APIs")
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(summary = "List notifications")
    @GetMapping
    public Result<PageView<NotificationView>> list(NotificationQueryRequest request) {
        return Result.success(notificationService.list(request));
    }

    @Operation(summary = "Get notification snapshot")
    @GetMapping("/snapshot")
    public Result<NotificationSnapshotView> snapshot() {
        return Result.success(notificationService.snapshot());
    }

    @Operation(summary = "Get unread count")
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.success(Long.valueOf(notificationService.unreadCount()));
    }

    @Operation(summary = "Mark notification as read")
    @PostMapping("/{id}/read")
    public Result<NotificationView> read(@PathVariable("id") Long id) {
        return Result.success(notificationService.markRead(id));
    }

    @Operation(summary = "Mark all notifications as read")
    @PostMapping("/read-all")
    public Result<Void> readAll() {
        notificationService.markAllRead();
        return Result.success(null);
    }

    @Operation(summary = "Open notification SSE stream")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return notificationService.connect();
    }
}
