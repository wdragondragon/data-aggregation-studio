package com.jdragon.studio.nacos.compat.config;

import com.jdragon.studio.nacos.compat.support.NacosMd5Support;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

public class NacosCompatConfigPropertySource extends MapPropertySource {

    private final NacosConfigDataResource resource;

    private final String rawContent;

    private final String md5;

    public NacosCompatConfigPropertySource(String name, Map<String, Object> source, String rawContent,
            NacosConfigDataResource resource) {
        super(name, source);
        this.resource = resource;
        this.rawContent = rawContent;
        this.md5 = NacosMd5Support.md5(rawContent);
    }

    public NacosConfigDataResource getResource() {
        return resource;
    }

    public String getRawContent() {
        return rawContent;
    }

    public String getMd5() {
        return md5;
    }

}
