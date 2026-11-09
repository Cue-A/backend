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
 * 계정 설정의 비밀번호 변경. {@code LOCAL} 인증이 있는 계정만 가능합니다.
 *
 * <p>카카오 전용 계정은 비밀번호 자체가 없어 확인할 대상이 없습니다. "비밀번호 설정"
 * (OAuth 계정에 LOCAL 인증을 새로 추가하는 것)은 별도 기능이라 여기서 만들지 않습니다.
 */
@Service
@RequiredArgsConstructor
public class UserPasswordService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public void changePassword(String userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        UserAuth localAuth = user.authOf(Provider.LOCAL)
                .orElseThrow(() -> new BusinessException(ErrorCode.PASSWORD_NOT_SET));

        if (!passwordEncoder.matches(currentPassword, localAuth.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (!PasswordPolicy.isValid(newPassword)) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD_FORMAT);
        }

        localAuth.changePassword(passwordEncoder.encode(newPassword));
    }
}
