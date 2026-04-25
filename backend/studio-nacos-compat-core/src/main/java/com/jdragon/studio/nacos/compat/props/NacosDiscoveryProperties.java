package com.jdragon.studio.nacos.compat.props;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties("spring.cloud.nacos.discovery")
public class NacosDiscoveryProperties {

    private boolean enabled = true;

    private boolean registerEnabled = true;

    private String serverAddr;

    private String namespace;

    private String group = "DEFAULT_GROUP";

    private String clusterName = "DEFAULT";

    private String service;

    private String ip;

    private Integer port;

    private double weight = 1.0D;

    private boolean ephemeral = true;

    private boolean secure = false;

    private String accessKey;

    private String secretKey;

    private Map<String, String> metadata = new LinkedHashMap<>();

}
