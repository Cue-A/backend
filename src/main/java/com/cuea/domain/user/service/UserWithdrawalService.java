package com.cuea.domain.user.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.auth.service.TokenService;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴. {@code users} 행을 지우면 {@code docs/02-database.md} 의 삭제 정책대로
 * 종속 테이블(document, session, report 등)이 DB 레벨 {@code ON DELETE CASCADE} 로
 * 전부 같이 지워집니다 — 도메인별로 삭제 코드를 따로 짤 필요가 없습니다.
 *
 * <p>문서 삭제({@link com.cuea.domain.document.service.DocumentDeleteService})와
 * 달리 여기는 소프트 삭제가 아닙니다. 탈퇴는 "완전 삭제"가 기대되는 동작이고,
 * 되돌릴 수 없습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserWithdrawalService {

    private final UserRepository userRepository;
    private final TokenService tokenService;

    @Transactional
    public void withdraw(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        userRepository.delete(user);
        tokenService.revokeAll(userId);

        log.info("회원 탈퇴 userId={}", userId);
    }
}
