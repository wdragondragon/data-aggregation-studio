package com.jdragon.studio.dto.model.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class RuntimeDatasourceArchiveRequest extends RuntimeDatasourceProbeRequest {
    @NotEmpty
    private List<String> paths = new ArrayList<String>();
}
