package com.cuea.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 로그인 수단 하나. 한 사람이 이메일과 카카오를 동시에 가질 수 있습니다.
 *
 * <p>인덱스를 따로 만들지 않습니다. 조회에 쓰는 두 조합이 이미 UNIQUE 제약으로
 * 인덱스를 갖습니다. 중복해서 만들면 쓰기만 느려집니다.
 */
@Entity
@Table(name = "user_auth", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_auth_provider", columnNames = {"provider", "provider_id"}),
        @UniqueConstraint(name = "uk_user_auth_user_provider", columnNames = {"user_id", "provider"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAuth {

    @Id
    @Column(name = "auth_id", length = 50)
    private String authId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Provider provider;

    /** 카카오 회원번호. LOCAL 은 null. */
    @Column(name = "provider_id", length = 100)
    private String providerId;

    /** BCrypt 해시. LOCAL 만 채웁니다. */
    @Column(length = 255)
    private String password;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private UserAuth(User user, Provider provider, String providerId, String password) {
        this.authId = UUID.randomUUID().toString();
        this.user = user;
        this.provider = provider;
        this.providerId = providerId;
        this.password = password;
        this.createdAt = LocalDateTime.now();
    }

    static UserAuth of(User user, Provider provider, String providerId, String password) {
        return new UserAuth(user, provider, providerId, password);
    }

    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }
}
