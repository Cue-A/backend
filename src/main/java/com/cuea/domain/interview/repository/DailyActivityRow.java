package com.cuea.domain.interview.repository;

/**
 * 날짜별 완료 세션 수 한 줄. 홈 잔디(활동 히트맵)와 연습 일수 집계에 씁니다(Issue #114·#113).
 *
 * <p>{@code date} 는 이미 <b>Asia/Seoul</b> 로 변환된 날짜를 {@code YYYY-MM-DD} 문자열로 받습니다
 * (쿼리에서 {@code to_char((completed_at AT TIME ZONE 'Asia/Seoul'), 'YYYY-MM-DD')}). 네이티브
 * 결과의 {@code date} 타입({@code java.sql.Date})을 그대로 레코드에 담으면 엄격한 캐스팅에 걸리므로
 * 문자열로 받아 서비스가 {@link java.time.LocalDate} 로 파싱합니다. 엔티티·{@code report_data}(jsonb)는
 * 끌어오지 않습니다.
 *
 * @param date  KST 기준 연습(완료) 날짜, {@code YYYY-MM-DD}
 * @param count 그날 완료된 세션 수(재연습 포함, 같은 날 여러 건이면 그만큼)
 */
public record DailyActivityRow(String date, long count) {
}
