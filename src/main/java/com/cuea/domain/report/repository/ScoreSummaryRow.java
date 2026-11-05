package com.cuea.domain.report.repository;

import java.math.BigDecimal;

/**
 * 기간 내 유효 점수의 평균·최고 한 줄(Issue #114).
 *
 * <p>집계 대상은 소유자의 리포트 중 {@code status in (COMPLETED, PARTIAL)} 이고
 * {@code score_total IS NOT NULL} 인 것뿐입니다. {@code FAILED}·{@code PROCESSING}·점수 없는
 * 리포트는 애초에 제외하므로, 점수 없는 리포트를 0 으로 끌어내리지 않습니다.
 *
 * <p>대상이 하나도 없으면 집계 함수가 {@code NULL} 을 돌려주므로 {@code average}·{@code best} 가
 * 모두 null 입니다(서비스가 그대로 응답의 null 로 내보냄).
 *
 * <p>{@code average} 는 PostgreSQL {@code avg()} 가 {@code numeric} 을 돌려줘 {@link BigDecimal}
 * 입니다(네이티브 결과는 타입 변환 없이 매핑되므로 JDBC 타입과 맞춥니다). 반올림·정수 변환은 서비스에서.
 *
 * @param average 평균 점수(numeric). 대상 0건이면 null
 * @param best    최고 점수. 대상 0건이면 null
 */
public record ScoreSummaryRow(BigDecimal average, Integer best) {
}
