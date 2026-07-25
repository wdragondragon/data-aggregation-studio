package com.jdragon.studio.dto.model;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class RuntimeEndpointView {
    private Long id;
    private Long runtimeClusterId;
    private String mode;
    private String endpointMasked;
    private List<String> headerNames = new ArrayList<String>();
    private boolean hasToken;
    private Integer connectTimeoutMillis;
    private Integer readTimeoutMillis;
    private boolean enabled;
    private LocalDateTime lastTestedAt;
    private String lastTestStatus;
    private String lastTestMessage;
}
