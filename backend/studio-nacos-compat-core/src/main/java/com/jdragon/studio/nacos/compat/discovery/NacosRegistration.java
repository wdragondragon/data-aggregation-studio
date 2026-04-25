package com.jdragon.studio.nacos.compat.discovery;

import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.serviceregistry.Registration;

import java.net.URI;
import java.util.Map;

public class NacosRegistration extends DefaultServiceInstance implements Registration {

    private final String namespace;

    private final String group;

    private final String clusterName;

    private final double weight;

    private final boolean ephemeral;

    public NacosRegistration(String instanceId, String serviceId, String host, int port, boolean secure,
            Map<String, String> metadata, String namespace, String group, String clusterName, double weight,
            boolean ephemeral) {
        super(instanceId, serviceId, host, port, secure, metadata);
        this.namespace = namespace;
        this.group = group;
        this.clusterName = clusterName;
        this.weight = weight;
        this.ephemeral = ephemeral;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getGroup() {
        return group;
    }

    public String getClusterName() {
        return clusterName;
    }

    public double getWeight() {
        return weight;
    }

    public boolean isEphemeral() {
        return ephemeral;
    }

}
