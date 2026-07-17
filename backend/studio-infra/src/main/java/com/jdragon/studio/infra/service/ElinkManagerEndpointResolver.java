package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ElinkManagerEndpointResolver {

    private final ObjectProvider<DiscoveryClient> discoveryClientProvider;
    private final StudioPlatformProperties properties;
    private final AtomicInteger instanceCursor = new AtomicInteger();

    public ElinkManagerEndpointResolver(ObjectProvider<DiscoveryClient> discoveryClientProvider,
                                        StudioPlatformProperties properties) {
        this.discoveryClientProvider = discoveryClientProvider;
        this.properties = properties;
    }

    public URI resolve(String relativePath) {
        DiscoveryClient discoveryClient = discoveryClientProvider.getIfAvailable();
        if (discoveryClient == null) {
            throw new IllegalStateException("Nacos discovery is unavailable for eLink Manager");
        }
        String serviceName = requireText(settings().getServiceName(), "eLink service name is not configured");
        List<ServiceInstance> instances = discoveryClient.getInstances(serviceName);
        if (instances == null || instances.isEmpty()) {
            throw new IllegalStateException("No eLink service instance is available: " + serviceName);
        }
        ServiceInstance instance = instances.get(Math.floorMod(instanceCursor.getAndIncrement(), instances.size()));
        URI base = instance == null ? null : instance.getUri();
        if (base == null || !StringUtils.hasText(base.getScheme()) || !StringUtils.hasText(base.getHost())) {
            throw new IllegalStateException("The discovered eLink service instance is invalid");
        }
        String prefix = normalizePathPrefix(settings().getPathPrefix());
        String relative = normalizeRelativePath(relativePath);
        String managerPath = "/".equals(prefix) ? relative : prefix + relative;
        return appendPath(base, managerPath);
    }

    private URI appendPath(URI base, String suffix) {
        String basePath = StringUtils.hasText(base.getPath()) && !"/".equals(base.getPath())
                ? stripTrailingSlash(base.getPath()) : "";
        try {
            return new URI(base.getScheme(), null, base.getHost(), base.getPort(), basePath + suffix, null, null);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("The discovered eLink service address is invalid", ex);
        }
    }

    private String normalizePathPrefix(String value) {
        String path = requireText(value, "eLink path prefix is not configured");
        path = path.startsWith("/") ? path : "/" + path;
        return stripTrailingSlash(path);
    }

    private String normalizeRelativePath(String value) {
        String path = requireText(value, "eLink Manager relative path is required");
        if (path.contains("://") || path.contains("?") || path.contains("#")
                || path.equals("..") || path.startsWith("../") || path.contains("/../")) {
            throw new IllegalArgumentException("eLink Manager relative path is invalid");
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private String stripTrailingSlash(String value) {
        String result = value;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(message);
        }
        return value.trim();
    }

    private StudioPlatformProperties.ElinkProperties settings() {
        return properties.getAlert().getElink();
    }
}
