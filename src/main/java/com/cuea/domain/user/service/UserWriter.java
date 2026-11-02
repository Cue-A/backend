package com.cuea.domain.user.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB 쓰기만 담당합니다.
 *
 * <p>{@link UserWithdrawalService} 에서 분리한 이유는 토큰 폐기와 S3 삭제를
 * <b>커밋이 끝난 뒤</b>에 하기 위해서입니다. 트랜잭션 안에서 하면 커밋이 실패했을 때
 * 계정은 남았는데 토큰이나 파일만 사라집니다.
 */
@Component
@RequiredArgsConstructor
public class UserWriter {

    private final UserRepository userRepository;

    @Transactional
    public void delete(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        userRepository.delete(user);
    }
}
