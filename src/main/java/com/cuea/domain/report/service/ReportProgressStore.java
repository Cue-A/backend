package com.cuea.domain.report.service;

import com.cuea.infrastructure.websocket.message.ReportProgressStage;

import java.time.Duration;
import java.util.Optional;

/**
 * 분석 중인 리포트의 마지막 진행 단계를 들고 있습니다. 상태 조회 API 가 읽습니다.
 *
 * <p>DB 가 아니라 여기 두는 이유: 폴링이 끝나면 쓸모가 없는 값이고, 단계가 바뀔 때마다
 * 리포트 행을 갱신할 이유가 없습니다.
 *
 * <p>인터페이스로 둔 이유는 폴러와 조회 서비스를 Redis 없이 테스트하기 위해서입니다.
 *
 * <p><b>구현체는 예외를 밖으로 내보내지 않습니다.</b> 저장이 실패해 폴러로 예외가 새면
 * 리포트가 {@code INTERNAL_ERROR} 로 FAILED 가 됩니다. 진행 표시 때문에 리포트를 잃으면
 * 안 됩니다.
 */
public interface ReportProgressStore {

    void save(String reportId, ReportProgress progress, Duration ttl);

    /** 없거나 읽지 못하면 빈 값. */
    Optional<ReportProgress> find(String reportId);

    void delete(String reportId);

    /** @param progress 0~1. AI 가 주지 않으면 null */
    record ReportProgress(ReportProgressStage stage, Double progress) {
    }
}
