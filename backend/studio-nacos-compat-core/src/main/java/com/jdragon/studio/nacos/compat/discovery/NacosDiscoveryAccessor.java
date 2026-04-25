package com.jdragon.studio.nacos.compat.discovery;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ListView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.nacos.compat.http.NacosHttpClient;
import com.jdragon.studio.nacos.compat.http.NacosHttpResponse;
import com.jdragon.studio.nacos.compat.http.NacosLegacyAuthService;
import com.jdragon.studio.nacos.compat.model.NacosCompatMode;
import com.jdragon.studio.nacos.compat.model.NacosServerGeneration;
import com.jdragon.studio.nacos.compat.model.NacosServerInfo;
import com.jdragon.studio.nacos.compat.props.NacosCompatProperties;
import com.jdragon.studio.nacos.compat.props.NacosDiscoveryProperties;
import com.jdragon.studio.nacos.compat.props.NacosRootProperties;
import com.jdragon.studio.nacos.compat.support.NacosClientManager;
import com.jdragon.studio.nacos.compat.support.NacosJsonSupport;
import com.jdragon.studio.nacos.compat.support.NacosServerProbeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class NacosDiscoveryAccessor {

    private static final Logger log = LoggerFactory.getLogger(NacosDiscoveryAccessor.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final NacosHttpClient httpClient;

    private final NacosLegacyAuthService legacyAuthService;

    private final NacosServerProbeService probeService;

    private final NacosClientManager clientManager;

    private final NacosCompatProperties compatProperties;

    public NacosDiscoveryAccessor(NacosHttpClient httpClient, NacosLegacyAuthService legacyAuthService,
            NacosServerProbeService probeService, NacosClientManager clientManager,
            NacosCompatProperties compatProperties) {
        this.httpClient = httpClient;
        this.legacyAuthService = legacyAuthService;
        this.probeService = probeService;
        this.clientManager = clientManager;
        this.compatProperties = compatProperties;
    }

    public List<ServiceInstance> getInstances(NacosRootProperties rootProperties, NacosDiscoveryProperties discoveryProperties,
            String serviceId) {
        NacosServerInfo serverInfo = resolveServerInfo(discoveryProperties.getServerAddr());
        if (serverInfo.generation() == NacosServerGeneration.MODERN) {
            return getModernInstances(rootProperties, discoveryProperties, serviceId);
        }
        return getLegacyInstances(rootProperties, discoveryProperties, serviceId);
    }

    public List<String> getServices(NacosRootProperties rootProperties, NacosDiscoveryProperties discoveryProperties) {
        NacosServerInfo serverInfo = resolveServerInfo(discoveryProperties.getServerAddr());
        if (serverInfo.generation() == NacosServerGeneration.MODERN) {
            return getModernServices(rootProperties, discoveryProperties);
        }
        return getLegacyServices(rootProperties, discoveryProperties);
    }

    public void register(NacosRootProperties rootProperties, NacosDiscoveryProperties discoveryProperties,
            NacosRegistration registration) {
        NacosServerInfo serverInfo = resolveServerInfo(discoveryProperties.getServerAddr());
        if (serverInfo.generation() == NacosServerGeneration.MODERN) {
            registerModern(rootProperties, discoveryProperties, registration);
            return;
        }
        registerLegacy(rootProperties, discoveryProperties, registration);
    }

    public boolean isLegacyServer(NacosDiscoveryProperties discoveryProperties) {
        return resolveServerInfo(discoveryProperties.getServerAddr()).generation() == NacosServerGeneration.LEGACY;
    }

    public void deregister(NacosRootProperties rootProperties, NacosDiscoveryProperties discoveryProperties,
            NacosRegistration registration) {
        NacosServerInfo serverInfo = resolveServerInfo(discoveryProperties.getServerAddr());
        if (serverInfo.generation() == NacosServerGeneration.MODERN) {
            deregisterModern(rootProperties, discoveryProperties, registration);
            return;
        }
        deregisterLegacy(rootProperties, discoveryProperties, registration);
    }

    public void beatLegacy(NacosRootProperties rootProperties, NacosDiscoveryProperties discoveryProperties,
            NacosRegistration registration) {
        Map<String, String> form = new HashMap<>();
        form.put("serviceName", registration.getServiceId());
        form.put("groupName", registration.getGroup());
        form.put("namespaceId", registration.getNamespace());
        form.put("clusterName", registration.getClusterName());
        form.put("ip", registration.getHost());
        form.put("port", String.valueOf(registration.getPort()));
        String accessToken = this.legacyAuthService.getAccessToken(discoveryProperties.getServerAddr(),
                rootProperties.getUsername(), rootProperties.getPassword());
        if (StringUtils.hasText(accessToken)) {
            form.put("accessToken", accessToken);
        }
        NacosHttpResponse response = this.httpClient.putForm(discoveryProperties.getServerAddr(), "/nacos/v1/ns/instance/beat",
                Map.of(), form, Map.of(), this.compatProperties.getConfigReadTimeout());
        if (!response.is2xxSuccessful()) {
            throw new IllegalStateException("Legacy nacos heartbeat failed, status=" + response.statusCode()
                    + ", service=" + registration.getServiceId());
        }
    }

    private NacosServerInfo resolveServerInfo(String serverAddr) {
        if (this.compatProperties.getMode() == NacosCompatMode.LEGACY) {
            return new NacosServerInfo(NacosServerGeneration.LEGACY, "1.3.2",
                    NacosServerProbeService.LEGACY_STATE_PATH, serverAddr);
        }
        if (this.compatProperties.getMode() == NacosCompatMode.MODERN) {
            return new NacosServerInfo(NacosServerGeneration.MODERN, "3.0.3",
                    NacosServerProbeService.MODERN_SERVER_STATE_PATH, serverAddr);
        }
        return this.probeService.probe(serverAddr);
    }

    private List<ServiceInstance> getModernInstances(NacosRootProperties rootProperties,
            NacosDiscoveryProperties discoveryProperties, String serviceId) {
        try {
            NamingService namingService = this.clientManager.getNamingService(rootProperties, discoveryProperties);
            List<Instance> instances = namingService.selectInstances(serviceId, discoveryProperties.getGroup(), true);
            List<ServiceInstance> result = new ArrayList<>(instances.size());
            for (Instance instance : instances) {
                result.add(toServiceInstance(serviceId, discoveryProperties.getGroup(), instance));
            }
            return result;
        }
        catch (NacosException ex) {
            throw new IllegalStateException("Query nacos instances failed for serviceId=" + serviceId, ex);
        }
    }

    private List<ServiceInstance> getLegacyInstances(NacosRootProperties rootProperties,
            NacosDiscoveryProperties discoveryProperties, String serviceId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("serviceName", serviceId);
        query.put("groupName", discoveryProperties.getGroup());
        query.put("namespaceId", discoveryProperties.getNamespace());
        query.put("healthyOnly", "true");
        String accessToken = this.legacyAuthService.getAccessToken(discoveryProperties.getServerAddr(),
                rootProperties.getUsername(), rootProperties.getPassword());
        if (StringUtils.hasText(accessToken)) {
            query.put("accessToken", accessToken);
        }
        NacosHttpResponse response = this.httpClient.get(discoveryProperties.getServerAddr(), "/nacos/v1/ns/instance/list",
                query, Map.of(), this.compatProperties.getConfigReadTimeout());
        if (!response.is2xxSuccessful()) {
            throw new IllegalStateException("Query legacy nacos instances failed, status=" + response.statusCode()
                    + ", serviceId=" + serviceId);
        }
        JsonNode jsonNode = NacosJsonSupport.readTree(response.body());
        JsonNode hosts = jsonNode.get("hosts");
        List<ServiceInstance> result = new ArrayList<>();
        if (hosts != null && hosts.isArray()) {
            for (JsonNode host : hosts) {
                result.add(toLegacyServiceInstance(serviceId, discoveryProperties.getGroup(), host));
            }
        }
        return result;
    }

    private List<String> getModernServices(NacosRootProperties rootProperties, NacosDiscoveryProperties discoveryProperties) {
        try {
            NamingService namingService = this.clientManager.getNamingService(rootProperties, discoveryProperties);
            List<String> services = new ArrayList<>();
            int pageNo = 1;
            while (true) {
                ListView<String> view = namingService.getServicesOfServer(pageNo, 1000, discoveryProperties.getGroup());
                if (view == null || view.getData() == null || view.getData().isEmpty()) {
                    break;
                }
                services.addAll(view.getData());
                if (view.getData().size() < 1000) {
                    break;
                }
                pageNo++;
            }
            return new ArrayList<>(new LinkedHashSet<>(services));
        }
        catch (NacosException ex) {
            throw new IllegalStateException("Query nacos services failed", ex);
        }
    }

    private List<String> getLegacyServices(NacosRootProperties rootProperties, NacosDiscoveryProperties discoveryProperties) {
        List<String> services = new ArrayList<>();
        int pageNo = 1;
        while (true) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("pageNo", String.valueOf(pageNo));
            query.put("pageSize", "1000");
            query.put("groupName", discoveryProperties.getGroup());
            query.put("namespaceId", discoveryProperties.getNamespace());
            String accessToken = this.legacyAuthService.getAccessToken(discoveryProperties.getServerAddr(),
                    rootProperties.getUsername(), rootProperties.getPassword());
            if (StringUtils.hasText(accessToken)) {
                query.put("accessToken", accessToken);
            }
            NacosHttpResponse response = this.httpClient.get(discoveryProperties.getServerAddr(), "/nacos/v1/ns/service/list",
                    query, Map.of(), this.compatProperties.getConfigReadTimeout());
            if (!response.is2xxSuccessful()) {
                throw new IllegalStateException("Query legacy nacos services failed, status=" + response.statusCode());
            }
            JsonNode jsonNode = NacosJsonSupport.readTree(response.body());
            JsonNode arrayNode = jsonNode.get("doms");
            if (arrayNode == null || !arrayNode.isArray()) {
                arrayNode = jsonNode.get("serviceList");
            }
            if (arrayNode == null || !arrayNode.isArray() || arrayNode.isEmpty()) {
                break;
            }
            arrayNode.forEach(node -> services.add(node.asText()));
            if (arrayNode.size() < 1000) {
                break;
            }
            pageNo++;
        }
        return new ArrayList<>(new LinkedHashSet<>(services));
    }

    private void registerModern(NacosRootProperties rootProperties, NacosDiscoveryProperties discoveryProperties,
            NacosRegistration registration) {
        try {
            NamingService namingService = this.clientManager.getNamingService(rootProperties, discoveryProperties);
            Instance instance = new Instance();
            instance.setIp(registration.getHost());
            instance.setPort(registration.getPort());
            instance.setClusterName(registration.getClusterName());
            instance.setWeight(registration.getWeight());
            instance.setEphemeral(registration.isEphemeral());
            instance.setEnabled(true);
            instance.setHealthy(true);
            instance.setMetadata(new LinkedHashMap<>(registration.getMetadata()));
            namingService.registerInstance(registration.getServiceId(), registration.getGroup(), instance);
        }
        catch (NacosException ex) {
            throw new IllegalStateException("Register nacos service failed for serviceId=" + registration.getServiceId(), ex);
        }
    }

    private void registerLegacy(NacosRootProperties rootProperties, NacosDiscoveryProperties discoveryProperties,
            NacosRegistration registration) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("serviceName", registration.getServiceId());
        form.put("groupName", registration.getGroup());
        form.put("namespaceId", registration.getNamespace());
        form.put("clusterName", registration.getClusterName());
        form.put("ip", registration.getHost());
        form.put("port", String.valueOf(registration.getPort()));
        form.put("weight", String.valueOf(registration.getWeight()));
        form.put("ephemeral", String.valueOf(registration.isEphemeral()));
        form.put("enabled", "true");
        form.put("healthy", "true");
        form.put("metadata", writeJson(registration.getMetadata()));
        String accessToken = this.legacyAuthService.getAccessToken(discoveryProperties.getServerAddr(),
                rootProperties.getUsername(), rootProperties.getPassword());
        if (StringUtils.hasText(accessToken)) {
            form.put("accessToken", accessToken);
        }
        NacosHttpResponse response = this.httpClient.postForm(discoveryProperties.getServerAddr(), "/nacos/v1/ns/instance",
                Map.of(), form, Map.of(), this.compatProperties.getConfigReadTimeout());
        if (!response.is2xxSuccessful()) {
            throw new IllegalStateException("Register legacy nacos service failed, status=" + response.statusCode()
                    + ", service=" + registration.getServiceId());
        }
    }

    private void deregisterModern(NacosRootProperties rootProperties, NacosDiscoveryProperties discoveryProperties,
            NacosRegistration registration) {
        try {
            NamingService namingService = this.clientManager.getNamingService(rootProperties, discoveryProperties);
            Instance instance = new Instance();
            instance.setIp(registration.getHost());
            instance.setPort(registration.getPort());
            instance.setClusterName(registration.getClusterName());
            instance.setEphemeral(registration.isEphemeral());
            namingService.deregisterInstance(registration.getServiceId(), registration.getGroup(), instance);
        }
        catch (NacosException ex) {
            throw new IllegalStateException("Deregister nacos service failed for serviceId=" + registration.getServiceId(), ex);
        }
    }

    private void deregisterLegacy(NacosRootProperties rootProperties, NacosDiscoveryProperties discoveryProperties,
            NacosRegistration registration) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("serviceName", registration.getServiceId());
        query.put("groupName", registration.getGroup());
        query.put("namespaceId", registration.getNamespace());
        query.put("clusterName", registration.getClusterName());
        query.put("ip", registration.getHost());
        query.put("port", String.valueOf(registration.getPort()));
        query.put("ephemeral", String.valueOf(registration.isEphemeral()));
        String accessToken = this.legacyAuthService.getAccessToken(discoveryProperties.getServerAddr(),
                rootProperties.getUsername(), rootProperties.getPassword());
        if (StringUtils.hasText(accessToken)) {
            query.put("accessToken", accessToken);
        }
        NacosHttpResponse response = this.httpClient.delete(discoveryProperties.getServerAddr(), "/nacos/v1/ns/instance",
                query, Map.of(), this.compatProperties.getConfigReadTimeout());
        if (!response.is2xxSuccessful()) {
            throw new IllegalStateException("Deregister legacy nacos service failed, status=" + response.statusCode()
                    + ", service=" + registration.getServiceId());
        }
    }

    private ServiceInstance toServiceInstance(String serviceId, String group, Instance instance) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (instance.getMetadata() != null) {
            metadata.putAll(instance.getMetadata());
        }
        metadata.put("nacos.cluster", Objects.toString(instance.getClusterName(), ""));
        metadata.put("nacos.weight", String.valueOf(instance.getWeight()));
        metadata.put("nacos.healthy", String.valueOf(instance.isHealthy()));
        return new DefaultServiceInstance(instance.getInstanceId(), serviceId, instance.getIp(), instance.getPort(),
                false, metadata);
    }

    private ServiceInstance toLegacyServiceInstance(String serviceId, String group, JsonNode host) {
        Map<String, String> metadata = new LinkedHashMap<>();
        JsonNode metadataNode = host.get("metadata");
        if (metadataNode != null && metadataNode.isObject()) {
            metadataNode.fields().forEachRemaining(entry -> metadata.put(entry.getKey(), entry.getValue().asText("")));
        }
        metadata.put("nacos.cluster", host.path("clusterName").asText(""));
        metadata.put("nacos.weight", host.path("weight").asText("1.0"));
        metadata.put("nacos.healthy", host.path("healthy").asText("true"));
        return new DefaultServiceInstance(host.path("instanceId").asText(serviceId + "@" + host.path("ip").asText()),
                serviceId, host.path("ip").asText(), host.path("port").asInt(), false, metadata);
    }

    private String writeJson(Map<String, String> metadata) {
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata == null ? Map.of() : metadata);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Serialize nacos metadata failed", ex);
        }
    }

}
