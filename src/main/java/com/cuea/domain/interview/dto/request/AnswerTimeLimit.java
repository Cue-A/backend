package com.cuea.domain.interview.dto.request;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;

/**
 * {@code POST /api/interviews} 요청의 답변 제한 시간 필드({@code answerTimeLimitSec})
 * 전용 3상태 래퍼입니다. <b>필드 누락</b>과 <b>명시적 {@code null}</b> 을 구분하기 위해
 * 존재합니다.
 *
 * <p>record + 평범한 {@code Integer} 필드만으로는 Jackson 역직렬화 후 두 경우가 모두
 * {@code null} 로 같아져 구분할 수 없습니다. 프론트 기본 선택값(90초)을 "필드 누락"에만
 * 적용하고, 사용자가 직접 고른 "제한 없음"(명시적 null)은 그대로 살리려면 이 구분이
 * 필요합니다(Issue #99 R3 리뷰).
 *
 * <h3>세 가지 상태</h3>
 * <ul>
 *   <li>{@link #ABSENT} — JSON 에 {@code answerTimeLimitSec} 키가 아예 없음.
 *       Jackson 이 creator 프로퍼티를 채우지 않아 {@link Deserializer#getAbsentValue}
 *       로 들어옵니다. 서비스가 기본값 90 을 적용합니다.</li>
 *   <li>{@link #UNLIMITED} — 키는 있고 값이 {@code null}("제한 없음"을 사용자가 직접 선택).
 *       {@link Deserializer#getNullValue} 로 들어옵니다. 기본값을 적용하지 않고
 *       {@code duration_limit} 에 null 로 저장합니다.</li>
 *   <li>값 보유 — {@code 60}·{@code 90}·{@code 120} 등 정수. 허용값 검증은 서비스가 합니다.</li>
 * </ul>
 *
 * <p>이 타입은 <b>요청 역직렬화 경계에서만</b> 씁니다. 응답 DTO·엔티티·DB 컬럼은
 * 그대로 {@code Integer}(제한 없음이면 null)를 쓰며, 기존 계약을 바꾸지 않습니다.
 * 새 의존성을 추가하지 않고 Jackson 기본 기능(absent vs null 콜백)만 사용합니다.
 */
@JsonDeserialize(using = AnswerTimeLimit.Deserializer.class)
public final class AnswerTimeLimit {

    /** 필드 자체가 요청에 없었음. 서비스가 기본값(90초)을 적용합니다. */
    public static final AnswerTimeLimit ABSENT = new AnswerTimeLimit(State.ABSENT, null);

    /** 명시적 {@code null}("제한 없음"). 기본값을 적용하지 않습니다. */
    public static final AnswerTimeLimit UNLIMITED = new AnswerTimeLimit(State.UNLIMITED, null);

    private enum State { ABSENT, UNLIMITED, PRESENT }

    private final State state;
    private final Integer value;

    private AnswerTimeLimit(State state, Integer value) {
        this.state = state;
        this.value = value;
    }

    /** 명시적 정수값을 담은 상태를 만듭니다(허용값 검증은 서비스가 합니다). */
    public static AnswerTimeLimit of(int seconds) {
        return new AnswerTimeLimit(State.PRESENT, seconds);
    }

    /** 요청에 필드 자체가 없었는지("필드 누락"). */
    public boolean isAbsent() {
        return state == State.ABSENT;
    }

    /** 사용자가 명시적 {@code null}("제한 없음")을 보냈는지. */
    public boolean isExplicitUnlimited() {
        return state == State.UNLIMITED;
    }

    /** 명시적 정수값을 보냈는지. {@code true} 일 때만 {@link #value()} 가 유효합니다. */
    public boolean hasValue() {
        return state == State.PRESENT;
    }

    /** 명시적 정수값. {@link #hasValue()} 가 {@code true} 일 때만 호출하세요. */
    public Integer value() {
        return value;
    }

    /**
     * {@link AnswerTimeLimit} 전용 역직렬화기. Jackson 은 JSON 키가 <b>있을 때만</b>
     * {@link #deserialize} 를 호출하고, 값이 {@code null} 이면 {@link #getNullValue},
     * 키가 <b>없으면</b> {@link #getAbsentValue} 를 호출합니다. 이 세 경로를 각각
     * {@code PRESENT}·{@code UNLIMITED}·{@code ABSENT} 로 매핑해 누락과 명시 null 을
     * 구분합니다.
     */
    static final class Deserializer extends JsonDeserializer<AnswerTimeLimit> {

        /** 키가 있고 값이 정수(또는 숫자)일 때. 숫자가 아니면 Jackson 기본 처리로 타입 오류. */
        @Override
        public AnswerTimeLimit deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            return AnswerTimeLimit.of(p.getIntValue());
        }

        /** 키는 있고 값이 명시적 {@code null} — 사용자가 고른 "제한 없음". */
        @Override
        public AnswerTimeLimit getNullValue(DeserializationContext ctxt) {
            return AnswerTimeLimit.UNLIMITED;
        }

        /** JSON 에 키가 아예 없음 — 필드 누락. 서비스가 기본값 90 을 적용. */
        @Override
        public AnswerTimeLimit getAbsentValue(DeserializationContext ctxt) {
            return AnswerTimeLimit.ABSENT;
        }
    }
}
