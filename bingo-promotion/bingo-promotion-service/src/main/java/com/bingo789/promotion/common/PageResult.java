package com.bingo789.promotion.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;
import java.util.function.Function;

public record PageResult<T>(List<T> records, long total, long page, long size) {

    private static final long MAX_PAGE_SIZE = 100;

    public static <E, T> PageResult<T> of(IPage<E> page, Function<E, T> mapper) {
        return new PageResult<>(page.getRecords().stream().map(mapper).toList(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** Clamps client-supplied paging parameters. */
    public static <E> Page<E> request(long page, long size) {
        return new Page<>(Math.max(1, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
    }
}
