package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class DataServiceFieldView {
    private String fieldName;
    private String fieldType;
    private String exampleValue;
    private String description;
}
