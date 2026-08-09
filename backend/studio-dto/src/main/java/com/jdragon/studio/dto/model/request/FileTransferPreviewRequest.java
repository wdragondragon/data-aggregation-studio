package com.jdragon.studio.dto.model.request;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class FileTransferPreviewRequest {
    private Integer limit = 200;
    private Map<String, String> parameters = new LinkedHashMap<String, String>();
}
