package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class PythonPackageSummaryView extends BaseDefinition {
    private String name;
    private String normalizedName;
    private String latestVersion;
    private String artifactType;
    private Integer versionCount;
    private Long latestSizeBytes;
    private Long artifactStoreId;
    private Boolean enabled;
    private LocalDateTime latestUploadedAt;
}
