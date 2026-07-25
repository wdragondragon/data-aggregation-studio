package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "worker_lease", autoResultMap = true)
public class WorkerLeaseEntity extends BaseTenantEntity {
    private Long runtimeClusterId;
    private String runtimeClusterCode;
    private String workerGroupCode;
    private String workerCode;
    private String workerKind;
    private String instanceId;
    private String bootId;
    private String runtimeVersion;
    private String pluginFingerprint;
    private String hostName;
    private String podName;
    private String nodeName;
    private String status;
    private LocalDateTime lastHeartbeatAt;
    private LocalDateTime leaseExpiresAt;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> capabilitiesJson = new LinkedHashMap<String, Object>();
}
