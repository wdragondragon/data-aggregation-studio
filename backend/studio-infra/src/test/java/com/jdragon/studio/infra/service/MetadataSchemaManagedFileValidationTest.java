package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.FieldComponentType;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetadataSchemaManagedFileValidationTest {

    @Test
    void managedFileFieldRequiresKnownPolicyAndSupportedValueType() {
        MetadataFieldDefinition field = field(FieldComponentType.MANAGED_FILE, FieldValueType.STRING, null);
        assertThrows(StudioException.class,
                () -> MetadataSchemaService.validateManagedFileFields(List.of(field)));

        field.setFilePolicyCode("unknown");
        assertThrows(StudioException.class,
                () -> MetadataSchemaService.validateManagedFileFields(List.of(field)));

        field.setFilePolicyCode("kerberos_keytab");
        field.setValueType(FieldValueType.OBJECT);
        assertThrows(StudioException.class,
                () -> MetadataSchemaService.validateManagedFileFields(List.of(field)));
    }

    @Test
    void validManagedFilePolicyIsNormalized() {
        MetadataFieldDefinition field = field(
                FieldComponentType.MANAGED_FILE, FieldValueType.ARRAY, "kerberos_keytab");

        MetadataSchemaService.validateManagedFileFields(List.of(field));

        assertEquals("KERBEROS_KEYTAB", field.getFilePolicyCode());
    }

    @Test
    void ordinaryFieldCannotCarryManagedFilePolicy() {
        MetadataFieldDefinition field = field(
                FieldComponentType.INPUT, FieldValueType.STRING, "GENERAL_CONFIG");

        assertThrows(StudioException.class,
                () -> MetadataSchemaService.validateManagedFileFields(List.of(field)));
    }

    private MetadataFieldDefinition field(FieldComponentType componentType,
                                          FieldValueType valueType,
                                          String policyCode) {
        MetadataFieldDefinition field = new MetadataFieldDefinition();
        field.setFieldKey("authFile");
        field.setComponentType(componentType);
        field.setValueType(valueType);
        field.setFilePolicyCode(policyCode);
        return field;
    }
}
