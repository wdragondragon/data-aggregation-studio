package com.jdragon.studio.nacos.compat.props;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties("spring.cloud.nacos.config")
public class NacosConfigProperties {

    private boolean enabled = true;

    private String serverAddr;

    private String namespace;

    private String group = "DEFAULT_GROUP";

    private String fileExtension = "properties";

    private String encode = "UTF-8";

    private String accessKey;

    private String secretKey;

    private boolean refreshEnabled = true;

    private ImportCheck importCheck = new ImportCheck();

    private List<ConfigItem> sharedConfigs = new ArrayList<>();

    private List<ConfigItem> extensionConfigs = new ArrayList<>();

    @Getter
    @Setter
    public static class ConfigItem {

        private String dataId;

        private String group;

        private String namespace;

        private boolean refresh;

    }

    @Getter
    @Setter
    public static class ImportCheck {

        private boolean enabled = true;

    }

}
