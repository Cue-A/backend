package com.cuea.domain.home.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 홈 대시보드 상단 통계 카드 중 <b>기간과 상관없는</b> 두 장(Issue #113).
 *
 * <ul>
 *   <li>{@code thisMonth} — "이번 달" 카드: 이번 달 연습한 날 수와 지난달 같은 기간 대비 증감</li>
 *   <li>{@code streak} — "연속 학습" 카드: 오늘(또는 어제)까지 이어진 연속 일수와 역대 최고</li>
 * </ul>
 *
 * <p>기간 탭을 따르는 "기간 평균"·"총 연습"·잔디·점수 추이는 {@code GET /api/home/practice}
 * (Issue #114)가 따로 내려줍니다. 한 화면의 통계 카드지만 데이터 출처가 둘로 나뉩니다.
 *
 * <p>모든 날짜 집계는 <b>Asia/Seoul</b> 기준이고, 완료({@code COMPLETED})된 세션만 셉니다.
 * 프론트 응답 타입({@code frontend} {@code src/domain/home/api/homeResponse.ts})의
 * {@code thisMonth}·{@code streak} 모양과 맞춥니다.
 */
@Schema(description = "홈 연습 통계(기간 무관)")
public record HomeStatsResponse(

        @Schema(description = "이번 달 연습 일수와 지난달 같은 기간 대비 증감")
        ThisMonth thisMonth,

        @Schema(description = "연속 학습(스트릭) 현재/최고")
        Streak streak
) {

    /**
     * "이번 달" 카드.
     *
     * @param month            이번 달(1~12). Asia/Seoul 기준 "오늘"의 달
     * @param activeDays       이번 달 1일~오늘 중 연습(완료)한 <b>날 수</b>(같은 날 여러 번 완료해도 1)
     * @param diffFromLastMonth 지난달 <b>같은 기간</b>(1일~오늘과 같은 일자) 대비 증감.
     *                          지난달 해당 기간에 기록이 전혀 없으면 null(월초 왜곡 방지).
     *                          <p><b>프론트 연동 주의:</b> {@code null} 은 "증감 0" 이 아니라
     *                          <b>"비교할 지난달 기록이 없음"</b> 입니다. 0 으로 바꾸지 말고 "지난달 기록 없음"
     *                          같은 문구로 표시하세요(값이 있으면 "지난달 이맘때보다 +N일"). 0 은 실제로 같은
     *                          경우에만 내려갑니다.
     */
    @Schema(description = "이번 달 연습 일수")
    public record ThisMonth(
            int month,
            int activeDays,

            @Schema(description = "지난달 같은 기간 대비 증감. null = 비교할 지난달 기록 없음(증감 0 아님)")
            Integer diffFromLastMonth
    ) {
    }

    /**
     * "연속 학습" 카드.
     *
     * @param currentDays 오늘(기록 있으면)·없으면 어제부터 거꾸로 끊길 때까지의 연속 일수.
     *                    오늘·어제 모두 기록이 없으면 0
     * @param bestDays    전체 완료 기록 중 가장 길게 이어진 연속 일수(중복 날짜 제거). 기록이 없으면 0
     */
    @Schema(description = "연속 학습 일수")
    public record Streak(
            int currentDays,
            int bestDays
    ) {
    }
}
