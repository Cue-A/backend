package com.cuea.domain.user.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 사람 하나 = 이 테이블 한 행. 로그인 수단은 몇 개든 {@link UserAuth} 로 붙습니다.
 *
 * <p><b>테이블명이 {@code user} 가 아니라 {@code users} 입니다.</b>
 * {@code user} 는 PostgreSQL 예약어라 {@code create table user} 가 문법 오류로
 * 죽습니다. 바꾸지 마세요.
 *
 * <p>{@code email} 이 nullable 인 것이 중요합니다. 카카오 이메일은 선택 동의라
 * 안 줄 수 있습니다. PostgreSQL 의 UNIQUE 는 NULL 중복을 허용해 제약은 유지됩니다.
 *
 * <p>비밀번호는 여기가 아니라 {@link UserAuth} 에 있습니다. 소셜 전용 계정은
 * 비밀번호가 없고, 한 사람이 이메일과 카카오를 동시에 쓸 수 있기 때문입니다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @Column(name = "user_id", length = 50)
    private String userId;

    @Column(length = 255, unique = true)
    private String email;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<UserAuth> auths = new ArrayList<>();

    private User(String email, String nickname) {
        this.userId = UUID.randomUUID().toString();
        this.email = email;
        this.nickname = nickname;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * @param email 카카오가 이메일을 안 줬으면 {@code null}
     */
    public static User create(String email, String nickname) {
        return new User(email, nickname);
    }

    /** 카카오가 닉네임도 안 줬을 때 쓰는 기본값. */
    public static String fallbackNickname(String userId) {
        return "면접자" + userId.replace("-", "").substring(0, 6);
    }

    /** 이 사람에게 로그인 수단을 하나 연결합니다. */
    public UserAuth link(Provider provider, String providerId, String password) {
        UserAuth auth = UserAuth.of(this, provider, providerId, password);
        auths.add(auth);
        return auth;
    }

    public Optional<UserAuth> authOf(Provider provider) {
        return auths.stream().filter(a -> a.getProvider() == provider).findFirst();
    }

    public List<Provider> providers() {
        return auths.stream().map(UserAuth::getProvider).toList();
    }
}
