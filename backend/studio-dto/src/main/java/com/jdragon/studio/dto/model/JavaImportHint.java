package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class JavaImportHint {
    private String simpleName;
    private String qualifiedName;
    private String packageName;
    private String source;
    private Long environmentDependencyId;
}
