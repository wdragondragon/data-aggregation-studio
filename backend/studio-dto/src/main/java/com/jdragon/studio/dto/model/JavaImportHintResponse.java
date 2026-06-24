package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class JavaImportHintResponse {
    private Long environmentId;
    private Long environmentVersion;
    private LocalDateTime generatedAt;
    private List<JavaImportHint> classes = new ArrayList<JavaImportHint>();
}
