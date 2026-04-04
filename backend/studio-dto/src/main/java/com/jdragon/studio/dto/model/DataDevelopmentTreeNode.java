package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.ScriptType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DataDevelopmentTreeNode {
    private String nodeKey;
    private String nodeType;
    private Long directoryId;
    private Long scriptId;
    private Long parentId;
    private String name;
    private String permissionCode;
    private ScriptType scriptType;
    private String datasourceName;
    private List<DataDevelopmentTreeNode> children = new ArrayList<DataDevelopmentTreeNode>();
}
