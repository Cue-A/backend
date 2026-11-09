package com.cuea.domain.growth.dto.response;

import java.time.OffsetDateTime;

/**
 * 성장 관리 응답의 배지 한 칸(Issue #131). 프론트 {@code growth/api/growthResponse.ts} 의
 * {@code badges[]} 아이템과 같은 모양입니다.
 *
 * <p><b>이 DTO 는 배지 도메인이 독립적으로 제공하는 조각입니다.</b> {@code GET /api/growth}
 * 엔드포인트와 전체 응답 래퍼({@code GrowthOverviewResponse})는 성장 관리(#129)가 소유하며,
 * 그 응답의 {@code badges} 필드를 {@code BadgeQueryService.overview()} 결과(이 DTO 의 리스트)로
 * 채웁니다. 중복 컨트롤러는 만들지 않습니다.
 *
 * @param badgeCode {@code badge.badge_code}. 프론트가 이 값으로 아이콘을 고릅니다(FIRST_SESSION 등)
 * @param badgeName 한국어 배지명(프론트 홈 배지 목업 기준)
 * @param tone      배지 색(brand/warning/danger/info/success). badge_code 로 정해집니다
 * @param earnedAt  획득 시각(ISO-8601). 미획득이면 null — 프론트가 회색으로 표시합니다
 */
public record BadgeView(
        String badgeCode,
        String badgeName,
        String tone,
        OffsetDateTime earnedAt
) {
}
