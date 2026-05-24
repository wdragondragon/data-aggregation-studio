package com.jdragon.studio.infra.service;

final class WorkflowRunStatusSupport {

    String normalizeSummaryStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return null;
        }
        return status.trim().toUpperCase();
    }
}
