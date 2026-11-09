package com.cuea.domain.user.service;

import com.cuea.domain.auth.service.TokenService;
import com.cuea.infrastructure.file.UserFileCleaner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 회원 탈퇴. {@code users} 행을 지우면 {@code docs/02-database.md} 의 삭제 정책대로
 * 종속 테이블(document, session, report 등)이 DB 레벨 {@code ON DELETE CASCADE} 로
 * 전부 같이 지워집니다 — 도메인별로 삭제 코드를 따로 짤 필요가 없습니다.
 *
 * <p>문서 삭제({@link com.cuea.domain.document.service.DocumentDeleteService})와
 * 달리 여기는 소프트 삭제가 아닙니다. 탈퇴는 "완전 삭제"가 기대되는 동작이고,
 * 되돌릴 수 없습니다.
 *
 * <h2>순서</h2>
 * <ol>
 *   <li>DB 삭제({@link UserWriter}). 유저 행을 잠그고 세션 ID 를 모은 뒤 지우는 것까지
 *       한 트랜잭션입니다 — 행이 지워지면 어떤 S3 키가 이 사용자 것인지 알 수 없으므로,
 *       수집과 삭제 사이에 새 세션이 끼어들지 못하게 묶습니다.</li>
 *   <li>커밋 뒤 전 기기 토큰 폐기와 S3 파일 삭제. 커밋이 실패하면 둘 다 하지 않습니다.
 *       둘 다 best-effort라 실패해도 로그만 남기고 탈퇴를 막지 않습니다. 토큰 폐기가
 *       실패해도 S3 정리는 이어서 진행합니다.</li>
 * </ol>
 *
 * <p>카카오 연결 끊기(unlink)는 아직 하지 않습니다. 어드민 키 설정이 필요해 후속 이슈로 뺐습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserWithdrawalService {

    private final UserWriter userWriter;
    private final TokenService tokenService;
    private final UserFileCleaner fileCleaner;

    public void withdraw(String userId) {
        List<String> sessionIds = userWriter.delete(userId);

        revokeAllTokens(userId);
        fileCleaner.removeAll(userId, sessionIds);

        log.info("회원 탈퇴 userId={}", userId);
    }

    /**
     * 커밋 이후 처리라 실패해도 탈퇴 자체는 되돌릴 수 없다. {@code revokeAll} 이 예외를 던져도
     * S3 정리는 계속 진행해야 하므로 best-effort 로 삼키고 로그만 남긴다. 남은 리프레시
     * 토큰이 있어도 {@code refresh} 가 유저 존재를 확인하므로 실사용은 불가능하다.
     */
    private void revokeAllTokens(String userId) {
        try {
            tokenService.revokeAll(userId);
        } catch (RuntimeException e) {
            log.error("탈퇴 중 토큰 폐기 실패 userId={}", userId, e);
        }
    }
}
