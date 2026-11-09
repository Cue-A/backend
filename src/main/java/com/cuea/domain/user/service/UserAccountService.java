package com.cuea.domain.user.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.user.dto.response.AccountResponse;
import com.cuea.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 계정 설정 화면의 조회만 담당합니다. 이메일·비밀번호 변경은 각각 별도 서비스가 있습니다. */
@Service
@RequiredArgsConstructor
public class UserAccountService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public AccountResponse getAccount(String userId) {
        return userRepository.findById(userId)
                .map(AccountResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
