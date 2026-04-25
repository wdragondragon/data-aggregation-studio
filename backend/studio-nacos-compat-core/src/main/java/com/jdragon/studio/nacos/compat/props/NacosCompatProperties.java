package com.jdragon.studio.nacos.compat.props;

import com.jdragon.studio.nacos.compat.model.NacosCompatMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties("platform.nacos.compat")
public class NacosCompatProperties {

    private NacosCompatMode mode = NacosCompatMode.AUTO;

    private Duration probeTimeout = Duration.ofSeconds(3);

    private boolean logProbeDetail = true;

    private Duration configReadTimeout = Duration.ofSeconds(3);

    private Duration legacyLongPollingTimeout = Duration.ofSeconds(30);

    private Duration legacyBeatInterval = Duration.ofSeconds(5);

}
