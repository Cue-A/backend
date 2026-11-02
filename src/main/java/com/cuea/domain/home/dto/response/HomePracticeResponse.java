package com.cuea.domain.home.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 홈 연습 기록 카드(잔디 + 점수 추이)와 기간 통계 카드(기간 평균·총 연습) 한 묶음(Issue #114).
 *
 * <p>기간 탭({@code 4w|10w|6m|1y})을 바꿀 때마다 프론트가 이 한 응답을 다시 받습니다
 * ({@code frontend} {@code src/domain/home/hooks/usePracticeRecord.ts}). 모든 날짜·점수 집계는
 * <b>Asia/Seoul</b> 기준이고, 활동은 완료({@code COMPLETED}) 세션, 점수는 열람 가능하고 점수가 있는
 * ({@code COMPLETED}·{@code PARTIAL} + {@code scoreTotal != null}) 리포트만 셉니다.
 *
 * <p><b>활동 날짜와 점수 날짜의 기준이 다릅니다.</b> 잔디·총연습·활동일은 <b>세션 완료 시각</b>
 * ({@code session.completedAt}), 평균·최고·추이는 <b>리포트 완료 시각</b>({@code report.completedAt})
 * 기준입니다. 세션은 면접이 끝난 순간, 리포트는 AI 분석이 끝난 순간이라 보통 몇 분~수십 분 차이가 납니다.
 * 자정 근처에 끝낸 한 번의 연습이 <b>잔디에서는 그날, 점수 추이에서는 다음 날</b>에 찍힐 수 있습니다
 * (드문 경계 사례). 두 영역의 합계가 날짜 단위로 꼭 일치하지는 않으며, 이는 의도된 동작입니다.
 *
 * @param period       요청한 기간 코드("4w" 등)
 * @param startDate    기간 첫날(YYYY-MM-DD, KST). {@code 오늘 - (days - 1)}
 * @param endDate      기간 마지막 날(= 오늘, KST)
 * @param days         기간의 <b>모든 날</b>(쉰 날 {@code count:0} 포함), 날짜 오름차순
 * @param totalCount   기간 내 완료 세션 수(재연습 포함)
 * @param activeDays   기간 내 연습한 고유 날 수(같은 날 여러 번도 1)
 * @param averageScore 기간 평균 점수(반올림 정수). 유효 점수가 없으면 null(0 아님)
 * @param bestScore    기간 최고 점수. 유효 점수가 없으면 null
 * @param trend        점수 추이(회차별 또는 월 평균)
 */
@Schema(description = "홈 연습 기록(기간 의존): 잔디·점수 추이·기간 통계")
public record HomePracticeResponse(

        @Schema(description = "기간 코드", example = "4w")
        String period,

        @Schema(description = "기간 첫날(YYYY-MM-DD, KST)")
        String startDate,

        @Schema(description = "기간 마지막 날(= 오늘, YYYY-MM-DD, KST)")
        String endDate,

        @Schema(description = "기간의 모든 날. 쉰 날은 count=0")
        List<ActivityDay> days,

        @Schema(description = "기간 내 완료 세션 수(재연습 포함)")
        long totalCount,

        @Schema(description = "기간 내 연습한 고유 날 수")
        int activeDays,

        @Schema(description = "기간 평균 점수(반올림). 유효 점수 없으면 null")
        Integer averageScore,

        @Schema(description = "기간 최고 점수. 유효 점수 없으면 null")
        Integer bestScore,

        @Schema(description = "점수 추이")
        ScoreTrend trend
) {

    /**
     * 잔디 한 칸.
     *
     * @param date  YYYY-MM-DD(KST)
     * @param count 그날 완료 세션 수(0 = 쉰 날). 색·단계는 프론트가 계산
     */
    @Schema(description = "날짜별 연습 횟수")
    public record ActivityDay(String date, long count) {
    }

    /**
     * 점수 추이.
     *
     * @param unit   "session"(4w·10w, 회차별) 또는 "month"(6m·1y, 월 평균)
     * @param points 오래된 것부터
     */
    @Schema(description = "점수 추이. unit=session|month")
    public record ScoreTrend(String unit, List<ScorePoint> points) {
    }

    /**
     * 추이의 한 점. 표시용 한글 라벨은 프론트가 {@code date} 로 직접 만들 수 있지만, 바로 쓸 수
     * 있도록 서버도 {@code label} 을 함께 내려줍니다(둘 다 제공).
     *
     * @param date  회차별이면 "YYYY-MM-DD", 월 평균이면 "YYYY-MM"(KST 원본 값)
     * @param label 표시용 한글 라벨("9월 28일" · "2026년 9월")
     * @param score 점수(회차 점수 또는 월 평균 반올림)
     */
    @Schema(description = "추이 한 점")
    public record ScorePoint(String date, String label, int score) {
    }
}
