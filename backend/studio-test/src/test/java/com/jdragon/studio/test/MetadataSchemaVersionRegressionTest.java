package com.jdragon.studio.test;

import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.request.MetadataSchemaSaveRequest;
import com.jdragon.studio.infra.service.MetadataSchemaService;
import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class MetadataSchemaVersionRegressionTest extends StudioApiRegressionTestSupport {

    @Autowired
    private MetadataSchemaService metadataSchemaService;

    @Test
    void shouldResolveHistoricalTechnicalSchemaVersionToCurrentSchemaDefinition() {
        MetadataSchemaDefinition beforeUpdate = metadataSchemaService.findTechnicalMetaModel("mysql8", "table");
        Assertions.assertNotNull(beforeUpdate, "mysql8 table technical schema should exist before update");

        Long historicalVersionId = beforeUpdate.getCurrentVersionId();
        Assertions.assertNotNull(historicalVersionId, "historical version id should not be null");

        MetadataSchemaSaveRequest request = new MetadataSchemaSaveRequest();
        request.setSchemaId(beforeUpdate.getId());
        request.setSchemaCode(beforeUpdate.getSchemaCode());
        request.setSchemaName(beforeUpdate.getSchemaName());
        request.setObjectType(beforeUpdate.getObjectType());
        request.setTypeCode(beforeUpdate.getTypeCode());
        request.setDescription(beforeUpdate.getDescription());
        request.setFields(beforeUpdate.getFields());
        metadataSchemaService.saveDraft(request);

        MetadataSchemaDefinition afterUpdate = metadataSchemaService.findTechnicalMetaModel("mysql8", "table");
        Assertions.assertNotNull(afterUpdate, "mysql8 table technical schema should exist after update");
        Assertions.assertNotEquals(historicalVersionId, afterUpdate.getCurrentVersionId(), "saveDraft should create a new current version");

        MetadataSchemaDefinition resolved = metadataSchemaService.findSchemaByVersionId(historicalVersionId);
        Assertions.assertNotNull(resolved, "historical schema version id should still resolve to the owning schema");
        Assertions.assertEquals("technical:mysql8:table", resolved.getSchemaCode(), "historical version should resolve to mysql8 table schema");
        Assertions.assertEquals(afterUpdate.getCurrentVersionId(), resolved.getCurrentVersionId(), "resolved schema should expose the current published version");

        MetadataFieldDefinition physicalNameField = resolved.getFields().stream()
                .filter(field -> "physicalName".equals(field.getFieldKey()))
                .findFirst()
                .orElse(null);
        Assertions.assertNotNull(physicalNameField, "physicalName should exist in resolved schema");
        Assertions.assertTrue(Boolean.TRUE.equals(physicalNameField.getSearchable()), "physicalName should remain searchable after sync");
    }
}
