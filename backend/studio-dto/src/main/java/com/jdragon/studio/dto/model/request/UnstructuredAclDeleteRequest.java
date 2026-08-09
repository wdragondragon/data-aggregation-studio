package com.jdragon.studio.dto.model.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UnstructuredAclDeleteRequest {
    @NotNull
    private Long id;
}
