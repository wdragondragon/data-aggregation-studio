package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class JavaMemberHintResponse {
    private Long environmentId;
    private Long environmentVersion;
    private String className;
    private LocalDateTime generatedAt;
    private List<JavaMemberHint> members = new ArrayList<JavaMemberHint>();
}
