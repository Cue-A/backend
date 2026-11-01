package com.cuea.common.paging;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 목록 API 의 page · size 규칙. page 는 0부터이고, size 는 기본 20 · 최대 100 입니다.
 * 잘못된 값은 오류 대신 기본값으로 되돌립니다.
 */
public final class PageRequests {

    public static final int DEFAULT_SIZE = 20;

    /** 상한이 없으면 {@code size=100000} 한 방으로 전부 긁어갈 수 있습니다. */
    public static final int MAX_SIZE = 100;

    private PageRequests() {
    }

    public static Pageable of(Integer page, Integer size, Sort sort) {
        return PageRequest.of(page == null || page < 0 ? 0 : page, sizeOf(size), sort);
    }

    private static int sizeOf(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
