package com.jdragon.studio.dto.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class FileTransferManualRunRequest {
    /** Shared runtime cluster for all items. Legacy item-level cluster fields are read only for compatibility. */
    private Long runtimeClusterId;
    @Valid
    @NotEmpty(message = "At least one transfer item is required")
    private List<FileTransferManualItemRequest> items = new ArrayList<FileTransferManualItemRequest>();
    private Map<String, Object> policy = new LinkedHashMap<String, Object>();
    private Map<String, Object> runtime = new LinkedHashMap<String, Object>();
    private Map<String, String> parameters = new LinkedHashMap<String, String>();
    private Boolean autoStart = Boolean.TRUE;
}
