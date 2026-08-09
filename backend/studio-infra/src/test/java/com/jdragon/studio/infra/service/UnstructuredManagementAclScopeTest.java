package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.entity.UnstructuredPathAclEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnstructuredManagementAclScopeTest {

    private final UnstructuredManagementService service = new UnstructuredManagementService(
            null, null, null, null, null, null, null, null, null, null);

    @Test
    void directoryRuleAppliesToTheDirectoryAndItsDescendants() {
        UnstructuredPathAclEntity rule = rule("/reports", 1);

        assertTrue(service.matchesPath(rule, "/reports"));
        assertTrue(service.matchesPath(rule, "/reports/2026/result.csv"));
        assertFalse(service.matchesPath(rule, "/reports-archive/result.csv"));
    }

    @Test
    void fileRuleAppliesOnlyToTheExactFile() {
        UnstructuredPathAclEntity rule = rule("/reports/result.csv", 0);

        assertTrue(service.matchesPath(rule, "/reports/result.csv"));
        assertFalse(service.matchesPath(rule, "/reports/result.csv/child"));
    }

    @Test
    void legacyRuleWithoutScopeRemainsRecursive() {
        UnstructuredPathAclEntity rule = rule("/legacy", null);

        assertTrue(service.matchesPath(rule, "/legacy/child"));
    }

    private UnstructuredPathAclEntity rule(String path, Integer directory) {
        UnstructuredPathAclEntity rule = new UnstructuredPathAclEntity();
        rule.setPath(path);
        rule.setDirectory(directory);
        return rule;
    }
}
