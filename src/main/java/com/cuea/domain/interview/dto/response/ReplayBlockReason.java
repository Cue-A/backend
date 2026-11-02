package com.cuea.domain.interview.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 재연습을 <b>지금 시작할 수 없는 사유</b>. 재연습 옵션 조회 응답
 * ({@link ReplaySessionOptionsResponse#replayBlockReasons()})에 담깁니다.
 *
 * <p><b>조회 자체를 막는 값이 아닙니다.</b> 과거 세션의 옵션은 삭제된 문서·미검증 기업이
 * 연결돼 있어도 그대로 조회됩니다(docs/12-replay.md "조회와 재연습 시작 가능 여부의 분리").
 * 이 사유는 "조회는 됐지만 지금 이 조건으로 재연습 시작 API 를 호출하면 거부된다"는
 * <b>참고 정보</b>이며, 최종 판정은 언제나 재연습 시작 API 가 수행합니다.
 *
 * <p>각 값은 재연습 시작 API({@link com.cuea.domain.interview.service.InterviewStartService})가
 * 실제로 던지는 거부와 1:1 로 대응합니다. 시작 API 에 없는 거부를 여기서 만들지 않습니다.
 *
 * <ul>
 *   <li>{@link #DOCUMENT_DELETED} — 시작 API 의 {@code findUsableFileDocument} 가
 *       삭제된 문서를 못 찾아 {@code DOCUMENT_NOT_FOUND}(404)로 거부</li>
 *   <li>{@link #COMPANY_UNVERIFIED} — 시작 API 의 {@code findVerifiedCompany} 가
 *       {@code verified=false} 기업을 못 찾아 {@code INVALID_REQUEST}(400)로 거부</li>
 * </ul>
 *
 * <p>재연습 옵션은 사용자가 바꿀 수 없으므로(질문 재활용 기능의 기획 정책), 이 사유가
 * 있으면 프론트는 다른 문서·기업으로 교체하도록 유도하지 말고 "현재 조건으로는 재연습을
 * 시작할 수 없다"고 안내합니다.
 */
@Schema(description = "재연습을 지금 시작할 수 없는 사유. 조회는 되지만 시작 API 가 거부하는 조건")
public enum ReplayBlockReason {

    /** 최초 세션에 연결된 이력서가 삭제됨. 시작 API 는 {@code DOCUMENT_NOT_FOUND}(404). */
    @Schema(description = "최초 면접에 쓴 이력서가 삭제되어 재연습을 시작할 수 없습니다")
    DOCUMENT_DELETED("최초 면접에 사용한 이력서가 삭제되어 재연습을 시작할 수 없습니다"),

    /** 최초 세션에 연결된 기업이 미검증 상태. 시작 API 는 {@code INVALID_REQUEST}(400). */
    @Schema(description = "최초 면접에 쓴 기업 정보가 더 이상 제공되지 않아 재연습을 시작할 수 없습니다")
    COMPANY_UNVERIFIED("최초 면접에 사용한 기업 정보를 현재 사용할 수 없어 재연습을 시작할 수 없습니다");

    private final String message;

    ReplayBlockReason(String message) {
        this.message = message;
    }

    /** 사용자에게 보여줄 안내 문구. */
    public String getMessage() {
        return message;
    }
}
