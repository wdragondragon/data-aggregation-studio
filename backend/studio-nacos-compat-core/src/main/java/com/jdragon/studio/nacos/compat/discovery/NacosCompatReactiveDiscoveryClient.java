package com.jdragon.studio.nacos.compat.discovery;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

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
        return Flux.defer(() -> Flux.fromIterable(this.discoveryClient.getInstances(serviceId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<String> getServices() {
        return Flux.defer(() -> Flux.fromIterable(this.discoveryClient.getServices()))
                .subscribeOn(Schedulers.boundedElastic());
    }

}
