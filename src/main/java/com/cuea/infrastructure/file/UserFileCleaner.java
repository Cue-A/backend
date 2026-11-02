package com.cuea.infrastructure.file;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 회원 탈퇴 때 사용자의 S3 파일을 전부 지웁니다.
 *
 * <p>지우는 범위는 {@link ObjectKeys} 의 두 계층입니다.
 * <ul>
 *   <li>{@code resumes/{userId}/} — 이력서·포트폴리오 원본과 마크다운 사본</li>
 *   <li>{@code sessions/{sessionId}/} — 답변 녹음·영상과 AI 가 올린 질문 음성</li>
 * </ul>
 *
 * <p><b>best-effort 입니다.</b> DB 행은 이미 지워졌으므로 여기서 실패해도 탈퇴는
 * 성공으로 끝냅니다. 실패한 접두사는 로그로 남겨 손으로 정리합니다. users 행이 사라지면
 * 어떤 키가 이 사용자 것인지 DB 로는 더 찾을 수 없어서, 접두사를 로그에 꼭 남깁니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserFileCleaner {

    private final S3StorageService storage;

    public void removeAll(String userId, List<String> sessionIds) {
        removePrefix(ObjectKeys.resumePrefix(userId));
        sessionIds.forEach(sessionId -> removePrefix(ObjectKeys.sessionPrefix(sessionId)));
    }

    private void removePrefix(String prefix) {
        try {
            storage.deleteByPrefix(prefix);
        } catch (RuntimeException e) {
            log.error("탈퇴 사용자 S3 파일 삭제 실패. 고아 객체가 남습니다 prefix={}", prefix, e);
        }
    }
}
