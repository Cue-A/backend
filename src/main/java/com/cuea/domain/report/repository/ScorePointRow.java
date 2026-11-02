package com.cuea.domain.report.repository;

import java.time.Instant;

/**
 * 점수 추이(회차별, {@code unit=session}) 한 점(Issue #114, 4w·10w).
 *
 * <p>리포트 하나가 한 점입니다. {@code status in (COMPLETED, PARTIAL)} 이고
 * {@code score_total IS NOT NULL} 인 리포트만 뽑습니다. {@code completedAt} 은 KST 날짜 라벨을
 * 만들고 시간순 정렬을 안정화하는 데 씁니다(같은 날 여러 건이면 완료 시각으로 정렬, 그래도 같으면
 * {@code publicId} 로 고정).
 *
 * <p>{@code completed_at}({@code timestamptz})은 네이티브 결과 매핑에서 {@link Instant} 로
 * 들어옵니다(타임존 없는 순간). 서비스가 Asia/Seoul 로 옮겨 날짜·라벨을 만듭니다.
 *
 * @param completedAt 리포트 완료 순간(UTC instant). 서비스에서 KST 날짜 라벨로 변환
 * @param score       종합 점수(0~100, not null). 네이티브 결과 매핑이라 JDBC 타입({@link Integer})으로 받습니다
 */
public record ScorePointRow(Instant completedAt, Integer score) {
}
