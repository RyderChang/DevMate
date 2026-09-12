package com.devmate.common.api;

import java.util.List;

public record PageResult<T>(int page, int pageSize, long total, List<T> items) {
    public PageResult {
        items = List.copyOf(items);
    }
}
