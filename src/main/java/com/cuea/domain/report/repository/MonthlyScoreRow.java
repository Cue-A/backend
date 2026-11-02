package com.cuea.domain.report.repository;

import java.math.BigDecimal;

/**
 * 점수 추이(월 평균, {@code unit=month}) 한 점(Issue #114, 6m·1y).
 *
 * <p>KST 기준 같은 달의 유효 점수를 평균한 값입니다. 유효 점수가 하나도 없는 달은 행 자체가
 * 없으므로 "점수 없는 달을 0 으로" 만들지 않습니다.
 *
 * @param yearMonth KST 기준 "YYYY-MM"
 * @param average   그 달 유효 점수의 평균({@code avg()} 라 numeric → {@link BigDecimal}). 반올림은 서비스에서
 */
public record MonthlyScoreRow(String yearMonth, BigDecimal average) {
}
