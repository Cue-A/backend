package com.cuea.domain.document.entity;

/**
 * 프론트에 내보내는 인덱싱 상태.
 *
 * <p>DB 의 {@link DocumentStatus} 와 분리한 이유는 <b>두 값의 수명이 다르기</b>
 * 때문입니다. {@code DocumentStatus} 는 우리 쪽 파싱·저장 단계를 그대로 담는
 * 내부 값이라 단계가 늘면 늘어납니다. 반면 프론트가 알아야 하는 건 "이 문서로
 * 면접을 시작할 수 있는가" 하나뿐입니다. 내부 단계가 바뀔 때마다 프론트 분기가
 * 같이 깨지지 않도록 경계에서 좁혀 내보냅니다.
 */
public enum IndexStatus {

    /** 아직 면접에 쓸 수 없습니다. 프론트는 폴링하거나 비활성 표시합니다. */
    PROCESSING,

    /** 면접 시작 가능. */
    COMPLETED,

    /** 되돌릴 수 없는 실패. 재업로드를 안내합니다. */
    FAILED
}
