package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("so_pf_env_dep_file")
public class EnvironmentDependencyFileEntity extends BaseTenantEntity {
    private Long dependencyId;
    private String originalFileName;
    private String artifactType;
    private String objectKey;
    private String objectUrl;
    private String checksum;
    private Long sizeBytes;
    private Integer visible;
    private Integer runtimeArtifact;
    private Long sourceFileId;
    private Integer enabled;
}
