package com.cuea.domain.growth.repository;

import com.cuea.domain.growth.entity.Badge;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 배지 정의(마스터) 조회. {@code Company} 처럼 공용 마스터라 소유자가 없지만, 이 저장소의
 * 규약을 따라 {@code JpaRepository} 대신 {@link Repository} 만 상속합니다
 * (docs/01-conventions.md).
 *
 * <p>배지는 {@code badge_id} 가 아니라 {@code badge_code} 로 지목합니다({@code Badge} 주석).
 */
public interface BadgeRepository extends Repository<Badge, Long> {

    Optional<Badge> findByBadgeCode(String badgeCode);

    List<Badge> findAllByBadgeCodeIn(List<String> badgeCodes);

    List<Badge> findAll();

    Badge save(Badge badge);
}
