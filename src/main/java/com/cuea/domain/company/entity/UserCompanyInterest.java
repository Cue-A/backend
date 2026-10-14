package com.cuea.domain.company.entity;

import com.cuea.common.entity.BaseCreatedEntity;
import com.cuea.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 사용자가 관심 등록한 기업. {@code user} 와 {@code company} 의 N:M 연결 테이블입니다.
 *
 * <p>UNIQUE {@code (user_id, company_id)} 는 ERD 에 없지만 넣었습니다. 없으면
 * 관심 버튼을 두 번 누르는 것만으로 같은 행이 쌓입니다.
 */
@Entity
@Table(
        name = "user_company_interest",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_company_interest",
                columnNames = {"user_id", "company_id"}
        ),
        indexes = @Index(name = "idx_user_company_interest_company", columnList = "company_id")
)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserCompanyInterest extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Company company;
}
