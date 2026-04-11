package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class NotificationSnapshotView {
    private long unreadCount;
    private List<NotificationView> recentNotifications = new ArrayList<NotificationView>();
}
