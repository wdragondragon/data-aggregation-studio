package com.jdragon.studio.nacos.compat.config;

import com.jdragon.studio.nacos.compat.props.NacosCompatProperties;
import com.jdragon.studio.nacos.compat.props.NacosConfigProperties;
import com.jdragon.studio.nacos.compat.props.NacosRootProperties;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NacosConfigDataLocationResolver implements ConfigDataLocationResolver<NacosConfigDataResource>, Ordered {

    private static final String PREFIX = "nacos:";

    @Override
    public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        return location.hasPrefix(PREFIX);
    }

    @Override
    public List<NacosConfigDataResource> resolve(ConfigDataLocationResolverContext context, ConfigDataLocation location)
            throws ConfigDataResourceNotFoundException {
        Binder binder = context.getBinder();
        NacosRootProperties rootProperties = binder.bind("spring.cloud.nacos", Bindable.of(NacosRootProperties.class))
                .orElseGet(NacosRootProperties::new);
        NacosConfigProperties configProperties = binder
                .bind("spring.cloud.nacos.config", Bindable.of(NacosConfigProperties.class))
                .orElseGet(NacosConfigProperties::new);
        NacosCompatProperties compatProperties = binder
                .bind("platform.nacos.compat", Bindable.of(NacosCompatProperties.class))
                .orElseGet(NacosCompatProperties::new);
        if (!configProperties.isEnabled()) {
            return List.of();
        }
        String serverAddr = configProperties.getServerAddr();
        if (!StringUtils.hasText(serverAddr)) {
            throw new IllegalStateException("spring.cloud.nacos.config.server-addr is blank");
        }
        String importedDataId = location.getNonPrefixedValue(PREFIX);
        Map<String, NacosConfigDataResource> resources = new LinkedHashMap<>();
        if (StringUtils.hasText(importedDataId)) {
            addResource(resources, location.isOptional(), importedDataId, configProperties.getGroup(),
                    configProperties.getNamespace(), configProperties.getFileExtension(), configProperties.isRefreshEnabled(),
                    rootProperties, configProperties, compatProperties);
        }
        for (NacosConfigProperties.ConfigItem item : configProperties.getSharedConfigs()) {
            if (!StringUtils.hasText(item.getDataId())) {
                continue;
            }
            addResource(resources, location.isOptional(), item.getDataId(),
                    StringUtils.hasText(item.getGroup()) ? item.getGroup() : configProperties.getGroup(),
                    StringUtils.hasText(item.getNamespace()) ? item.getNamespace() : configProperties.getNamespace(),
                    getExtension(item.getDataId(), configProperties.getFileExtension()),
                    item.isRefresh(), rootProperties, configProperties, compatProperties);
        }
        for (NacosConfigProperties.ConfigItem item : configProperties.getExtensionConfigs()) {
            if (!StringUtils.hasText(item.getDataId())) {
                continue;
            }
            addResource(resources, location.isOptional(), item.getDataId(),
                    StringUtils.hasText(item.getGroup()) ? item.getGroup() : configProperties.getGroup(),
                    StringUtils.hasText(item.getNamespace()) ? item.getNamespace() : configProperties.getNamespace(),
                    getExtension(item.getDataId(), configProperties.getFileExtension()),
                    item.isRefresh(), rootProperties, configProperties, compatProperties);
        }
        return new ArrayList<>(resources.values());
    }

    private void addResource(Map<String, NacosConfigDataResource> resources, boolean optional, String dataId, String group,
            String namespace, String fileExtension, boolean refreshEnabled, NacosRootProperties rootProperties,
            NacosConfigProperties configProperties, NacosCompatProperties compatProperties) {
        String key = namespace + "|" + group + "|" + dataId;
        NacosConfigDataResource existing = resources.get(key);
        boolean mergedRefresh = refreshEnabled || (existing != null && existing.isRefreshEnabled());
        resources.put(key, new NacosConfigDataResource(optional, configProperties.getServerAddr(), namespace,
                rootProperties.getUsername(), rootProperties.getPassword(), configProperties.getAccessKey(),
                configProperties.getSecretKey(), configProperties.getEncode(), dataId, group, fileExtension,
                mergedRefresh, compatProperties.getMode(), compatProperties.getProbeTimeout(),
                compatProperties.isLogProbeDetail(), compatProperties.getConfigReadTimeout(),
                compatProperties.getLegacyLongPollingTimeout()));
    }

    private String getExtension(String dataId, String defaultExtension) {
        int separatorIndex = dataId.lastIndexOf('.');
        if (separatorIndex > -1 && separatorIndex < dataId.length() - 1) {
            return dataId.substring(separatorIndex + 1);
        }
        return defaultExtension;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

}
