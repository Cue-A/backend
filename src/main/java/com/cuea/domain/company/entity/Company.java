package com.cuea.domain.company.entity;

import com.cuea.common.entity.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 법인 단위 기업 정보. 인재상·요구역량을 담아 질문 생성의 재료로 씁니다.
 *
 * <p><b>공개 식별자가 없는 유일한 주요 테이블입니다.</b> 사용자 데이터가 아니라
 * 모두가 같은 목록을 보는 공용 마스터라서 {@code companyId} 를 그대로 노출해도
 * 남의 정보가 새지 않습니다.
 *
 * <p>이 테이블은 사용자가 아니라 <b>수집 배치가 채웁니다.</b> 그래서 시간 컬럼이
 * 둘 다 감사(audit) 용도가 아닙니다. {@link #getCollectedOn()} 설명을 보세요.
 */
@Entity
@Table(
        name = "company",
        indexes = {
                @Index(name = "idx_company_industry", columnList = "industry"),
                @Index(name = "idx_company_verified", columnList = "verified")
        }
)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Company extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "company_id")
    private Long companyId;

    /** 법인 정식 명칭. 이 값이 곧 자연키라 UNIQUE 입니다. */
    @Column(name = "company_name", nullable = false, unique = true, length = 100)
    private String companyName;

    @Column(nullable = false, length = 100)
    private String industry;

    @Enumerated(EnumType.STRING)
    @Column(name = "values_format", nullable = false, length = 20)
    private ValuesFormat valuesFormat;

    /**
     * 핵심 가치 3~6개. {@code [{"name": ..., "behaviors": [...]}]} 형태입니다.
     *
     * <p>기업마다 항목 수와 깊이가 달라 컬럼으로 펴지 않고 jsonb 로 둡니다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "core_values", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> coreValues;

    /** 직무를 키로 하는 요구역량. 공고에서 못 찾았으면 null. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "job_requirements", columnDefinition = "jsonb")
    private Map<String, Object> jobRequirements;

    /** 출처 URL. 인재상과 요구역량의 출처가 달라 복수입니다. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "source_url", nullable = false, columnDefinition = "text[]")
    private List<String> sourceUrl;

    /**
     * <b>수정 시각이 아니라 수집일입니다.</b> ERD 의 컬럼명이 {@code updated_at}
     * 이지만 타입이 {@code date} 이고 의미는 "이 정보를 언제 긁어왔는가" 입니다.
     *
     * <p>{@code BaseTimeEntity} 를 상속하지 않고 직접 선언한 이유가 이것입니다.
     * 자동 갱신되면 안 됩니다. 필드명을 {@code updatedAt} 으로 두면
     * {@code getUpdatedAt()} 을 보고 감사 컬럼으로 착각하기 쉬워 이름을 바꿨습니다.
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDate collectedOn;

    /** 공식 채용 페이지에서 직접 확인했는지. 추론으로 채운 값과 구분합니다. */
    @Column(nullable = false)
    private boolean verified;

    @Column(name = "talent_profile", columnDefinition = "text")
    private String talentProfile;

    @Column(name = "interview_style", length = 200)
    private String interviewStyle;
}
