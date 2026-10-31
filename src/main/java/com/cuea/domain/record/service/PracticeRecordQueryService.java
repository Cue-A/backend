package com.cuea.domain.record.service;

import com.cuea.common.paging.PageRequests;
import com.cuea.domain.interview.entity.InterviewSession;
import com.cuea.domain.interview.entity.SessionStatus;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.record.dto.request.PracticeRecordStatus;
import com.cuea.domain.record.dto.response.PracticeRecordListResponse;
import com.cuea.domain.record.dto.response.PracticeRecordResponse;
import com.cuea.domain.report.dto.response.ReportSummary;
import com.cuea.domain.report.service.ReportSummaryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 연습 기록 목록. 세션은 면접 서비스, 리포트 요약은 리포트 서비스로 읽어 합칩니다.
 * 중단된 세션은 리포트도 없고 이어서 할 수도 없어 보여주지 않습니다.
 */
@Service
@RequiredArgsConstructor
public class PracticeRecordQueryService {

    private static final List<SessionStatus> VISIBLE = List.of(SessionStatus.COMPLETED, SessionStatus.IN_PROGRESS);

    /** 같은 시각이면 세션 ID 순으로 고정해 페이지가 흔들리지 않게 합니다. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "sessionId");

    private final InterviewSessionQueryService sessionQueryService;
    private final ReportSummaryQueryService reportSummaryQueryService;

    /** 세션 1번 · 회차 1번 · 리포트 1번, 페이지 크기와 상관없이 쿼리 세 번입니다. */
    @Transactional(readOnly = true)
    public PracticeRecordListResponse list(String userId, PracticeRecordStatus status, UUID folderId,
                                           String keyword, Integer page, Integer size) {
        Page<InterviewSession> sessions = sessionQueryService.findRecords(
                userId, statusesOf(status), folderId, keyword, PageRequests.of(page, size, NEWEST_FIRST));
        List<InterviewSession> content = sessions.getContent();
        Map<String, Integer> rounds = sessionQueryService.roundsOf(userId, content);
        Map<String, ReportSummary> reports = reportSummaryQueryService.findBySessionIds(
                userId, content.stream().map(InterviewSession::getSessionId).toList());

        return PracticeRecordListResponse.from(sessions.map(s -> PracticeRecordResponse.of(
                s, rounds.get(s.getSessionId()), reports.get(s.getSessionId()))));
    }

    private static List<SessionStatus> statusesOf(PracticeRecordStatus status) {
        return status == null ? VISIBLE : List.of(status.toSessionStatus());
    }
}
