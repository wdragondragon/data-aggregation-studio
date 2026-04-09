package com.jdragon.studio.dto.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

@Schema(name = "PageView", description = "Generic paged response payload")
public class PageView<T> {

    @Schema(description = "Current page number, starting from 1")
    private int pageNo;

    @Schema(description = "Requested page size")
    private int pageSize;

    @Schema(description = "Total number of records")
    private long total;

    @Schema(description = "Current page items")
    private List<T> items = new ArrayList<T>();

    public static <T> PageView<T> of(int pageNo, int pageSize, long total, List<T> items) {
        PageView<T> view = new PageView<T>();
        view.setPageNo(pageNo);
        view.setPageSize(pageSize);
        view.setTotal(total);
        view.setItems(items == null ? new ArrayList<T>() : items);
        return view;
    }

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

    public List<T> getItems() {
        return items;
    }

    public void setItems(List<T> items) {
        this.items = items;
    }
}
