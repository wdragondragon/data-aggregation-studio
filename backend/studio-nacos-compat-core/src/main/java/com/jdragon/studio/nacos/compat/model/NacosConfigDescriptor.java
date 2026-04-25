package com.jdragon.studio.nacos.compat.model;

public record NacosConfigDescriptor(
        String dataId,
        String group,
        String namespace,
        String fileExtension,
        boolean refreshEnabled,
        boolean optional) {
}
