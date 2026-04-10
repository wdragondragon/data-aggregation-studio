package com.jdragon.studio.infra.service;

import java.util.ArrayList;
import java.util.List;

public class DataModelSyncBatchResult {

    private final List<DataModelSyncItemResult> items = new ArrayList<DataModelSyncItemResult>();

    public List<DataModelSyncItemResult> getItems() {
        return items;
    }

    public void addItem(DataModelSyncItemResult item) {
        if (item != null) {
            items.add(item);
        }
    }

    public int getSuccessCount() {
        int count = 0;
        for (DataModelSyncItemResult item : items) {
            if (item != null && item.isSuccess()) {
                count++;
            }
        }
        return count;
    }

    public int getFailureCount() {
        int count = 0;
        for (DataModelSyncItemResult item : items) {
            if (item != null && !item.isSuccess()) {
                count++;
            }
        }
        return count;
    }

    public boolean hasFailures() {
        return getFailureCount() > 0;
    }

    public boolean hasSuccesses() {
        return getSuccessCount() > 0;
    }

    public String firstFailureMessage() {
        for (DataModelSyncItemResult item : items) {
            if (item != null && !item.isSuccess() && item.getMessage() != null && !item.getMessage().trim().isEmpty()) {
                return item.getMessage();
            }
        }
        return null;
    }
}
