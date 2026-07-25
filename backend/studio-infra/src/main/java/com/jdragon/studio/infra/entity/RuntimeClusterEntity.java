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
@TableName(value = "studio_runtime_cluster", autoResultMap = true)
public class RuntimeClusterEntity extends BaseTenantEntity {
    private String code;
    private String name;
    private Integer enabled;
    private String status;
    private String version;
    private LocalDateTime lastHeartbeatAt;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> instancesJson = new LinkedHashMap<String, Object>();
}
