package com.bingo789.betrecord.web.dto;

import java.util.List;

/** @param page 1-based page number */
public record PageView<T>(List<T> items, long total, long page, long size) {
}
