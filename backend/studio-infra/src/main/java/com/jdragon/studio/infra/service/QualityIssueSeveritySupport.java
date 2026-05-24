package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.enums.QualityIssueSeverity;

final class QualityIssueSeveritySupport {

    int severityRank(QualityIssueSeverity severity) {
        if (severity == QualityIssueSeverity.LOW) {
            return 1;
        }
        if (severity == QualityIssueSeverity.MEDIUM) {
            return 2;
        }
        if (severity == QualityIssueSeverity.HIGH) {
            return 3;
        }
        if (severity == QualityIssueSeverity.CRITICAL) {
            return 4;
        }
        return 0;
    }
}
