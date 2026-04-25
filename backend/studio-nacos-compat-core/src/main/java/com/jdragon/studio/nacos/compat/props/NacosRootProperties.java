package com.jdragon.studio.nacos.compat.props;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties("spring.cloud.nacos")
public class NacosRootProperties {

    private String username;

    private String password;

}
