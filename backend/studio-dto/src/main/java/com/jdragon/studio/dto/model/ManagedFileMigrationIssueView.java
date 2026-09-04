package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ManagedFileMigrationIssueView {
    private Long datasourceId;
    private String datasourceName;
    private String datasourceTypeCode;
    private List<String> fieldKeys = new ArrayList<String>();
}
