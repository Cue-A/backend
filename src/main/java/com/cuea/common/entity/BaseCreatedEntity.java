package com.cuea.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;

/**
 * 생성 시각만 남기는 테이블의 공통 부모.
 *
 * <p>ERD 가 {@code timestamptz} 로 잡혀 있어 {@link OffsetDateTime} 을 씁니다.
 * {@code LocalDateTime} 을 쓰면 Hibernate 가 {@code timestamp}(타임존 없음)으로
 * 만들어 ERD 와 어긋납니다.
 *
 * <p>Spring Data Auditing({@code @CreatedDate}) 대신 JPA 콜백을 쓰는 이유는
 * {@code @EnableJpaAuditing} 설정 클래스 없이 엔티티만으로 완결되기 때문입니다.
 */
@MappedSuperclass
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class BaseCreatedEntity {

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void applyCreatedAt() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
