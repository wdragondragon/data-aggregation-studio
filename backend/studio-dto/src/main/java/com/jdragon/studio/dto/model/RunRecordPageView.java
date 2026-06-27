package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

@Schema(name = "RunRecordPageView", description = "Paged run record list with status counters")
public class RunRecordPageView {

    @Schema(description = "Current page number, starting from 1")
    private int pageNo;

    @Schema(description = "Requested page size")
    private int pageSize;

    @Schema(description = "Total number of records after filters")
    private long total;

    @Schema(description = "Failed record count after filters")
    private long failedCount;

    @Schema(description = "Running record count after filters")
    private long runningCount;

    @Schema(description = "Successful record count after filters")
    private long successCount;

    @Schema(description = "Current page run records")
    private List<RunRecordListView> items = new ArrayList<RunRecordListView>();

    public int getPageNo() {
        return pageNo;
    }

    public void setPageNo(int pageNo) {
        this.pageNo = pageNo;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(long failedCount) {
        this.failedCount = failedCount;
    }

    public long getRunningCount() {
        return runningCount;
    }

    public void setRunningCount(long runningCount) {
        this.runningCount = runningCount;
    }

    public long getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(long successCount) {
        this.successCount = successCount;
    }

    public List<RunRecordListView> getItems() {
        return items;
    }

    public void setItems(List<RunRecordListView> items) {
        this.items = items == null ? new ArrayList<RunRecordListView>() : items;
    }
}
