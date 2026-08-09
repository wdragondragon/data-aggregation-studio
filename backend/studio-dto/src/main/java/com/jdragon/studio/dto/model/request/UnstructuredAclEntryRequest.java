package com.jdragon.studio.dto.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UnstructuredAclEntryRequest {
    private Long id;
    @NotBlank
    private String principalType;
    private Long userId;
    @NotBlank
    private String permission;
    @NotBlank
    private String effect;
}
