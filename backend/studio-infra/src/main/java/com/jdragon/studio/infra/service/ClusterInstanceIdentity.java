package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.UUID;

@Component
public class ClusterInstanceIdentity {

    private final StudioPlatformProperties properties;
    private final String instanceId;
    private final String hostName;

    public ClusterInstanceIdentity(StudioPlatformProperties properties) {
        this.properties = properties;
        this.hostName = resolveHostName();
        this.instanceId = resolveInstanceId();
    }

    public String instanceId() {
        return instanceId;
    }

    public String hostName() {
        return hostName;
    }

    public String podName() {
        return firstText(properties.getPodName(), getenv("POD_NAME"), getenv("HOSTNAME"), hostName);
    }

    public String nodeName() {
        return firstText(properties.getNodeName(), getenv("NODE_NAME"));
    }

    private String resolveInstanceId() {
        return firstText(properties.getInstanceId(), getenv("STUDIO_INSTANCE_ID"), getenv("POD_UID"), getenv("HOSTNAME"),
                hostName + "-" + UUID.randomUUID());
    }

    private String resolveHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }

    private String getenv(String key) {
        String value = System.getenv(key);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String firstText(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }
}
