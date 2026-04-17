package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DatasourceTypeCapabilityView extends BaseDefinition {
    private String typeCode;
    private String typeName;
    private Boolean enabled;
    private Boolean readable;
    private Boolean writable;
    private Boolean executable;
    private Boolean sqlExecutable;
    private String sourceCategory;
    private String sourcePlugin;
    private List<String> readerPlugins = new ArrayList<String>();
    private List<String> writerPlugins = new ArrayList<String>();
    private Integer sortOrder;
    private String description;
}
