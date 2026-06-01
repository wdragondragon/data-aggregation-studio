package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class OpsCenterOptionsView {
    private List<String> executionTypes = new ArrayList<String>();
    private List<String> statuses = new ArrayList<String>();
    private List<String> workerGroups = new ArrayList<String>();
}
