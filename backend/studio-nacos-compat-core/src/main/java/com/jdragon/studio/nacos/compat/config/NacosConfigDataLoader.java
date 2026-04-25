package com.jdragon.studio.nacos.compat.config;

import com.jdragon.studio.nacos.compat.http.NacosHttpClient;
import com.jdragon.studio.nacos.compat.http.NacosLegacyAuthService;
import com.jdragon.studio.nacos.compat.support.NacosClientManager;
import com.jdragon.studio.nacos.compat.support.NacosServerProbeService;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException;

import java.io.IOException;
import java.util.List;

public class NacosConfigDataLoader implements ConfigDataLoader<NacosConfigDataResource> {

    @Override
    public ConfigData load(ConfigDataLoaderContext context, NacosConfigDataResource resource)
            throws IOException, ConfigDataResourceNotFoundException {
        NacosHttpClient httpClient = new NacosHttpClient(resource.getProbeTimeout());
        NacosLegacyAuthService authService = new NacosLegacyAuthService(httpClient, resource.getConfigReadTimeout());
        NacosServerProbeService probeService = new NacosServerProbeService(httpClient, toCompatProperties(resource));
        NacosClientManager clientManager = new NacosClientManager();
        NacosConfigAccessor accessor = new NacosConfigAccessor(httpClient, authService, probeService, clientManager);
        String content = accessor.loadConfig(resource);
        if ((content == null || content.isBlank()) && resource.isOptionalResource()) {
            return ConfigData.EMPTY;
        }
        String sourceName = "nacos-config:" + resource.getDataId() + "[" + resource.getGroup() + "]";
        NacosCompatConfigPropertySource propertySource = new NacosCompatConfigPropertySource(sourceName,
                NacosConfigContentSupport.load(sourceName, resource.getFileExtension(), content == null ? "" : content),
                content == null ? "" : content, resource);
        return new ConfigData(List.of(propertySource));
    }

    private com.jdragon.studio.nacos.compat.props.NacosCompatProperties toCompatProperties(NacosConfigDataResource resource) {
        com.jdragon.studio.nacos.compat.props.NacosCompatProperties properties =
                new com.jdragon.studio.nacos.compat.props.NacosCompatProperties();
        properties.setMode(resource.getCompatMode());
        properties.setProbeTimeout(resource.getProbeTimeout());
        properties.setLogProbeDetail(resource.isLogProbeDetail());
        properties.setConfigReadTimeout(resource.getConfigReadTimeout());
        properties.setLegacyLongPollingTimeout(resource.getLegacyLongPollingTimeout());
        return properties;
    }

}
