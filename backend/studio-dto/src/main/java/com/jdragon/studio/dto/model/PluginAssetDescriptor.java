package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.PluginCategory;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class PluginAssetDescriptor {
    private String code;
    private String pluginName;
    private PluginCategory pluginCategory;
    private String assetType;
    private String assetPath;
    private boolean executable;
    private Map<String, Object> metadata = new LinkedHashMap<String, Object>();
    private Map<String, Object> template = new LinkedHashMap<String, Object>();
}

