package com.jdragon.studio.nacos.compat.model;

public record NacosServerInfo(
        NacosServerGeneration generation,
        String version,
        String stateEndpoint,
        String baseServerAddress) {
}
