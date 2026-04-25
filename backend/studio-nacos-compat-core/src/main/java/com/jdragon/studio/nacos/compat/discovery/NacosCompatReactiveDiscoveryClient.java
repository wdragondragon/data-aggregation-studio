package com.jdragon.studio.nacos.compat.discovery;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import reactor.core.publisher.Flux;

public class NacosCompatReactiveDiscoveryClient implements ReactiveDiscoveryClient {

    private final NacosCompatDiscoveryClient discoveryClient;

    public NacosCompatReactiveDiscoveryClient(NacosCompatDiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
    }

    @Override
    public String description() {
        return "Nacos compatibility reactive discovery client";
    }

    @Override
    public Flux<ServiceInstance> getInstances(String serviceId) {
        return Flux.fromIterable(this.discoveryClient.getInstances(serviceId));
    }

    @Override
    public Flux<String> getServices() {
        return Flux.fromIterable(this.discoveryClient.getServices());
    }

}
