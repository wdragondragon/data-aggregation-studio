package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class QualityTaskPreviewView {
    private String resolvedSql;
    private List<String> warnings = new ArrayList<String>();
}
