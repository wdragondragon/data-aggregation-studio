package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.enums.FieldComponentType;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalMetadataFieldBuilderTest {

    @Test
    void exposesPerDatasourceLegacySshCompatibilitySwitchForSftpSources() {
        List<MetadataFieldDefinition> fields = new TechnicalMetadataFieldBuilder()
                .buildTechnicalFields("sftp", "source");

        MetadataFieldDefinition field = fields.stream()
                .filter(candidate -> "allowLegacyAlgorithms".equals(candidate.getFieldKey()))
                .findFirst()
                .orElseThrow();

        assertThat(field.getValueType()).isEqualTo(FieldValueType.BOOLEAN);
        assertThat(field.getComponentType()).isEqualTo(FieldComponentType.SWITCH);
        assertThat(field.getDefaultValue()).isEqualTo("false");
        assertThat(field.getFieldName()).contains("降低安全性");
    }
}
