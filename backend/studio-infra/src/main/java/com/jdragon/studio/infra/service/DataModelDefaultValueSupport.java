package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;

import java.math.BigDecimal;

final class DataModelDefaultValueSupport {

    Object parseDefaultValue(MetadataFieldDefinition field) {
        String defaultValue = field.getDefaultValue();
        if (defaultValue == null || defaultValue.trim().isEmpty()) {
            return null;
        }
        FieldValueType valueType = field.getValueType();
        if (valueType == null) {
            return defaultValue;
        }
        try {
            switch (valueType) {
                case BOOLEAN:
                    return Boolean.parseBoolean(defaultValue);
                case INTEGER:
                    return Integer.parseInt(defaultValue);
                case LONG:
                    return Long.parseLong(defaultValue);
                case DECIMAL:
                    return new BigDecimal(defaultValue);
                default:
                    return defaultValue;
            }
        } catch (Exception ignored) {
            return defaultValue;
        }
    }
}
