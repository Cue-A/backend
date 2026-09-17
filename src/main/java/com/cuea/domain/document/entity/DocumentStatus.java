package com.cuea.domain.document.entity;

/**
 * 문서의 내부 처리 상태. DB 의 {@code status} 컬럼입니다.
 *
 * <p>프론트로 그대로 나가지 않습니다. 경계에서 {@link IndexStatus} 로 좁혀
 * 내보냅니다. 이유는 {@code IndexStatus} 주석 참고.
 */
public enum DocumentStatus {

    /** 저장은 끝났지만 아직 본문을 읽지 못한 상태. */
    UPLOADED,

    /** AI 인덱싱 진행 중. */
    PARSING,

    /** 면접에 쓸 수 있는 상태. */
    READY,

    FAILED;

    public IndexStatus toIndexStatus() {
        return switch (this) {
            case UPLOADED, PARSING -> IndexStatus.PROCESSING;
            case READY -> IndexStatus.COMPLETED;
            case FAILED -> IndexStatus.FAILED;
        };
    }
}
