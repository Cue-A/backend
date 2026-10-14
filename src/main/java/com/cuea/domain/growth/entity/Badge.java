package com.cuea.domain.growth.entity;

import com.cuea.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * 배지 정의(마스터). 사용자가 획득한 기록은 {@link UserBadge} 입니다.
 *
 * <p>{@code Company} 와 마찬가지로 공용 마스터라 공개 식별자가 없습니다.
 * 대신 {@code badgeCode} 가 사람이 읽을 수 있는 안정적인 키입니다.
 * 코드에서 배지를 지목할 때는 {@code badgeId} 가 아니라 이 값을 쓰세요.
 * 시드 데이터를 다시 넣어도 안 바뀝니다.
 *
 * <p><b>획득 기록이 있는 배지는 지우지 마세요.</b> {@code user_badge} 의 FK 가
 * 막습니다(PostgreSQL 기본 {@code NO ACTION}). 더 안 주고 싶으면 지우지 말고
 * {@code status} 를 내리세요.
 */
@Entity
@Table(name = "badge")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Badge extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "badge_id")
    private Long badgeId;

    /** 사람이 읽는 안정적인 키. 예: {@code FIRST_SESSION}. */
    @Column(name = "badge_code", nullable = false, unique = true, length = 50)
    private String badgeCode;

    @Column(name = "badge_name", nullable = false, length = 50)
    private String badgeName;

    @Column(nullable = false, length = 255)
    private String description;

    /** 배지 묶음. 값 목록 미확정이라 문자열로 둡니다. */
    @Column(nullable = false, length = 20)
    private String category;

    /** 획득 조건의 종류. {@code conditionData} 를 어떻게 읽을지가 이 값으로 갈립니다. */
    @Column(name = "condition_type", nullable = false, length = 30)
    private String conditionType;

    /**
     * 획득 조건의 파라미터. 조건 종류마다 모양이 달라 jsonb 입니다.
     *
     * <p>예: 연속 답변 7일이면 {@code {"days": 7}}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_data", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> conditionData;

    @Column(name = "icon_url", length = 500)
    private String iconUrl;

    /** 지금도 주는 배지인지. 지우는 대신 이 값을 내립니다. */
    @Column(nullable = false, length = 20)
    private String status;
}
