package com.cuea.infrastructure.file;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    /**
     * 세션 접두사 삭제는 세션마다 S3 목록 조회·삭제 왕복이 따로 들어, 가상 스레드로
     * 동시에 돌립니다. {@code spring.threads.virtual.enabled} 가 켜져 있어 이 메서드를
     * 부르는 요청 스레드도 가상 스레드입니다.
     */
    public void removeAll(String userId, List<String> sessionIds) {
        removePrefix(ObjectKeys.resumePrefix(userId));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (String sessionId : sessionIds) {
                futures.add(executor.submit(() -> removePrefix(ObjectKeys.sessionPrefix(sessionId))));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.error("탈퇴 사용자 세션 S3 파일 삭제 중 예외. 고아 객체가 남을 수 있습니다 userId={}", userId, e);
        }
    }

    private void removePrefix(String prefix) {
        try {
            storage.deleteByPrefix(prefix);
        } catch (RuntimeException e) {
            log.error("탈퇴 사용자 S3 파일 삭제 실패. 고아 객체가 남습니다 prefix={}", prefix, e);
        }
    }
}
