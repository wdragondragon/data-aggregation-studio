package com.jdragon.studio.flink.service;

import com.jdragon.studio.commons.exception.StudioException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlinkSqlGuardTest {

    @Test
    void allowsSelectAndAddsDefaultLimit() {
        FlinkSqlGuard guard = new FlinkSqlGuard();

        assertEquals("SELECT * FROM m_1 LIMIT 500", guard.guardSelectSql("SELECT * FROM m_1", 500));
    }

    @Test
    void keepsExistingLimitAndStripsTrailingSemicolon() {
        FlinkSqlGuard guard = new FlinkSqlGuard();

        assertEquals("WITH t AS (SELECT * FROM m_1) SELECT * FROM t LIMIT 10",
                guard.guardSelectSql("WITH t AS (SELECT * FROM m_1) SELECT * FROM t LIMIT 10;", 500));
    }

    @Test
    void rejectsMutatingStatements() {
        FlinkSqlGuard guard = new FlinkSqlGuard();

        assertThrows(StudioException.class, () -> guard.guardSelectSql("INSERT INTO sink SELECT * FROM m_1", 500));
        assertThrows(StudioException.class, () -> guard.guardSelectSql("SELECT * FROM m_1; DROP TABLE m_1", 500));
    }
}
