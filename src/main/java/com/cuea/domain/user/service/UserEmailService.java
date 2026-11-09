package com.cuea.domain.user.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.user.entity.Provider;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.entity.UserAuth;
import com.cuea.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 설정의 이메일 변경. {@code LOCAL} 인증이 있는 계정만 가능합니다.
 *
 * <p>카카오 전용 계정은 이메일이 {@code KakaoAccountLinker} 의 계정 연동과 묶여 있어,
 * 우리 DB 에서만 바꾸면 다음 카카오 로그인 때 그 계정과 어긋납니다. 비밀번호 확인도
 * 할 수 없으므로(비밀번호 자체가 없음) 전면 거부합니다.
 */
@Service
@RequiredArgsConstructor
public class UserEmailService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public String changeEmail(String userId, String newEmail, String currentPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        UserAuth localAuth = user.authOf(Provider.LOCAL)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_CHANGE_NOT_ALLOWED));

        if (!passwordEncoder.matches(currentPassword, localAuth.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (!newEmail.equals(user.getEmail()) && userRepository.existsByEmail(newEmail)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        user.changeEmail(newEmail);
        return newEmail;
    }
}
