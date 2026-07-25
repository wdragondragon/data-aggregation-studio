package com.jdragon.studio.dto.model;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class RuntimeClusterView {
    private Long id;
    private String code;
    private String name;
    private boolean enabled;
    private String status;
    private String version;
    /** Project-scoped placement defaults; populated by the options endpoint. */
    private boolean preferred;
    private boolean allowManualOverride;
    private LocalDateTime lastHeartbeatAt;
    private int onlineInstanceCount;
    private List<RuntimeClusterInstanceView> instances = new ArrayList<RuntimeClusterInstanceView>();
}
