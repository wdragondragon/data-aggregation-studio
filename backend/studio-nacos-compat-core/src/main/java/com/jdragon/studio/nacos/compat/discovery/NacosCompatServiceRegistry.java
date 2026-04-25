package com.jdragon.studio.nacos.compat.discovery;

import com.jdragon.studio.nacos.compat.props.NacosCompatProperties;
import com.jdragon.studio.nacos.compat.props.NacosDiscoveryProperties;
import com.jdragon.studio.nacos.compat.props.NacosRootProperties;
import org.springframework.cloud.client.serviceregistry.ServiceRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NacosCompatServiceRegistry implements ServiceRegistry<NacosRegistration> {

    private final NacosDiscoveryAccessor accessor;

    private final NacosRootProperties rootProperties;

    private final NacosDiscoveryProperties discoveryProperties;

    private final NacosCompatProperties compatProperties;

    private final Map<String, ScheduledExecutorService> beatExecutors = new ConcurrentHashMap<>();

    public NacosCompatServiceRegistry(NacosDiscoveryAccessor accessor, NacosRootProperties rootProperties,
            NacosDiscoveryProperties discoveryProperties, NacosCompatProperties compatProperties) {
        this.accessor = accessor;
        this.rootProperties = rootProperties;
        this.discoveryProperties = discoveryProperties;
        this.compatProperties = compatProperties;
    }

    @Override
    public void register(NacosRegistration registration) {
        this.accessor.register(this.rootProperties, this.discoveryProperties, registration);
        if (registration.isEphemeral() && this.accessor.isLegacyServer(this.discoveryProperties)) {
            ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "nacos-legacy-beat-" + registration.getServiceId());
                thread.setDaemon(true);
                return thread;
            });
            executorService.scheduleAtFixedRate(() ->
                            this.accessor.beatLegacy(this.rootProperties, this.discoveryProperties, registration),
                    this.compatProperties.getLegacyBeatInterval().toSeconds(),
                    this.compatProperties.getLegacyBeatInterval().toSeconds(), TimeUnit.SECONDS);
            ScheduledExecutorService previous = this.beatExecutors.put(registration.getInstanceId(), executorService);
            if (previous != null) {
                previous.shutdownNow();
            }
        }
    }

    @Override
    public void deregister(NacosRegistration registration) {
        ScheduledExecutorService executorService = this.beatExecutors.remove(registration.getInstanceId());
        if (executorService != null) {
            executorService.shutdownNow();
        }
        this.accessor.deregister(this.rootProperties, this.discoveryProperties, registration);
    }

    @Override
    public void close() {
        this.beatExecutors.values().forEach(ScheduledExecutorService::shutdownNow);
        this.beatExecutors.clear();
    }

    @Override
    public void setStatus(NacosRegistration registration, String status) {
        // Nacos does not expose a Spring Cloud compatible status abstraction here.
    }

    @Override
    public <T> T getStatus(NacosRegistration registration) {
        return null;
    }

}
