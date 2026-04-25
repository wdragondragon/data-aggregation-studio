package com.jdragon.studio.nacos.compat.discovery;

import com.jdragon.studio.nacos.compat.props.NacosDiscoveryProperties;
import com.jdragon.studio.nacos.compat.props.NacosRootProperties;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.util.List;

public class NacosCompatDiscoveryClient implements DiscoveryClient {

    private final NacosDiscoveryAccessor accessor;

    private final NacosRootProperties rootProperties;

    private final NacosDiscoveryProperties discoveryProperties;

    public NacosCompatDiscoveryClient(NacosDiscoveryAccessor accessor, NacosRootProperties rootProperties,
            NacosDiscoveryProperties discoveryProperties) {
        this.accessor = accessor;
        this.rootProperties = rootProperties;
        this.discoveryProperties = discoveryProperties;
    }

    @Override
    public String description() {
        return "Nacos compatibility discovery client";
    }

    @Override
    public List<ServiceInstance> getInstances(String serviceId) {
        return this.accessor.getInstances(this.rootProperties, this.discoveryProperties, serviceId);
    }

    @Override
    public List<String> getServices() {
        return this.accessor.getServices(this.rootProperties, this.discoveryProperties);
    }

}
