package com.jdragon.studio.nacos.compat.support;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingService;
import com.jdragon.studio.nacos.compat.props.NacosConfigProperties;
import com.jdragon.studio.nacos.compat.props.NacosDiscoveryProperties;
import com.jdragon.studio.nacos.compat.props.NacosRootProperties;

import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class NacosClientManager {

    private final ConcurrentMap<String, NamingService> namingServices = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, ConfigService> configServices = new ConcurrentHashMap<>();

    public NamingService getNamingService(NacosRootProperties rootProperties, NacosDiscoveryProperties discoveryProperties) {
        String key = "naming|" + discoveryProperties.getServerAddr() + "|" + discoveryProperties.getNamespace()
                + "|" + rootProperties.getUsername();
        return this.namingServices.computeIfAbsent(key, ignored -> {
            try {
                return NacosFactory.createNamingService(createProperties(rootProperties, discoveryProperties.getServerAddr(),
                        discoveryProperties.getNamespace(), discoveryProperties.getAccessKey(),
                        discoveryProperties.getSecretKey()));
            }
            catch (Exception ex) {
                throw new IllegalStateException("Create nacos naming service failed", ex);
            }
        });
    }

    public ConfigService getConfigService(NacosRootProperties rootProperties, NacosConfigProperties configProperties) {
        String key = "config|" + configProperties.getServerAddr() + "|" + configProperties.getNamespace()
                + "|" + rootProperties.getUsername();
        return this.configServices.computeIfAbsent(key, ignored -> {
            try {
                return NacosFactory.createConfigService(createProperties(rootProperties, configProperties.getServerAddr(),
                        configProperties.getNamespace(), configProperties.getAccessKey(), configProperties.getSecretKey()));
            }
            catch (Exception ex) {
                throw new IllegalStateException("Create nacos config service failed", ex);
            }
        });
    }

    private Properties createProperties(NacosRootProperties rootProperties, String serverAddr, String namespace,
            String accessKey, String secretKey) {
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, serverAddr);
        if (namespace != null && !namespace.isBlank()) {
            properties.put(PropertyKeyConst.NAMESPACE, namespace);
        }
        if (rootProperties.getUsername() != null && !rootProperties.getUsername().isBlank()) {
            properties.put(PropertyKeyConst.USERNAME, rootProperties.getUsername());
        }
        if (rootProperties.getPassword() != null && !rootProperties.getPassword().isBlank()) {
            properties.put(PropertyKeyConst.PASSWORD, rootProperties.getPassword());
        }
        if (accessKey != null && !accessKey.isBlank()) {
            properties.put(PropertyKeyConst.ACCESS_KEY, accessKey);
        }
        if (secretKey != null && !secretKey.isBlank()) {
            properties.put(PropertyKeyConst.SECRET_KEY, secretKey);
        }
        return properties;
    }

}
