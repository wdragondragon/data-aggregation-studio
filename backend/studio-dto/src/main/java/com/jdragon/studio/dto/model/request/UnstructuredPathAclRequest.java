package com.jdragon.studio.dto.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;

@Data
public class UnstructuredPathAclRequest {
    private Long datasourceId;
    @NotBlank
    private String path;
    private Boolean directory = Boolean.TRUE;
    private java.util.List<UnstructuredAclEntryRequest> entries = new ArrayList<UnstructuredAclEntryRequest>();
}
