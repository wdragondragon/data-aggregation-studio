package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RuntimeDatasourceHydrationResultView {
    private List<RuntimeDatasourceHydrationItemView> items = new ArrayList<RuntimeDatasourceHydrationItemView>();
}
