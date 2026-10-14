package com.cuea.domain.calendar.entity;

/** 면접 진행 방식. 어느 컬럼이 필요한지가 이 값으로 갈립니다. */
public enum InterviewMethod {

    /** 화상 면접. {@code meeting_url} 이 필요합니다. */
    ONLINE,

    /** 대면 면접. {@code location} 이 필요합니다. */
    OFFLINE
}
