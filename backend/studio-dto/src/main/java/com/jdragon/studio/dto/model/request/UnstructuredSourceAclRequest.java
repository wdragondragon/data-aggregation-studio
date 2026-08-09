package com.jdragon.studio.dto.model.request;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class UnstructuredSourceAclRequest {
    private Long datasourceId;
    private List<UnstructuredAclEntryRequest> entries = new ArrayList<UnstructuredAclEntryRequest>();
}
