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
    private String workerCode;
    private String workerKind;
    private String hostName;
    private String status;
    private LocalDateTime lastHeartbeatAt;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> capabilitiesJson = new LinkedHashMap<String, Object>();
}
