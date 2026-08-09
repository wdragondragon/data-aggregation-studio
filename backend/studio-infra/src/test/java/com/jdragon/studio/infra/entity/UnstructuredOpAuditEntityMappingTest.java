package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UnstructuredOpAuditEntityMappingTest {

    @Test
    void shouldEscapeRecursiveAuditColumnForMysql() throws Exception {
        Field field = UnstructuredOpAuditEntity.class.getDeclaredField("recursive");
        TableField tableField = field.getAnnotation(TableField.class);

        assertNotNull(tableField);
        assertEquals("`recursive`", tableField.value());
    }
}
