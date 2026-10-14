package com.cuea.domain.user.repository;

import com.cuea.domain.user.entity.Provider;
import com.cuea.domain.user.entity.UserAuth;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAuthRepository extends JpaRepository<UserAuth, String> {

    /** 소셜 로그인 진입점. 카카오 회원번호로 이미 연결된 계정을 찾습니다. */
    Optional<UserAuth> findByProviderAndProviderId(Provider provider, String providerId);

    /** 이 사람이 해당 수단을 이미 걸어뒀는지. */
    Optional<UserAuth> findByUser_UserIdAndProvider(String userId, Provider provider);
}
