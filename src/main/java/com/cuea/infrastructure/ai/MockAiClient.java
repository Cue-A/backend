package com.cuea.infrastructure.ai;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.infrastructure.ai.dto.AiAnswerSubmitRequest;
import com.cuea.infrastructure.ai.dto.AiCompany;
import com.cuea.infrastructure.ai.dto.AiQuestionResult;
import com.cuea.infrastructure.ai.dto.AiSessionStartRequest;
import com.cuea.infrastructure.ai.dto.AiSessionStartResponse;
import com.cuea.infrastructure.ai.dto.AiTaskStatusResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 서버가 없을 때 쓰는 내부 목입니다. {@code app.ai.mock.enabled=true} 로 켭니다.
 *
 * <p>이게 없으면 AI 서버가 나올 때까지 폴링·WebSocket·로그 저장을 전혀 검증할 수
 * 없습니다. 실제 AI 처럼 <b>바로 결과를 주지 않고 두 번은 processing 을 반환</b>해서
 * 폴링 경로가 실제로 도는지 확인할 수 있게 했습니다.
 *
 * <p>카테고리는 AI 가 쓰는 8개 값 중에서 고릅니다. 가운뎃점(·)까지 같아야 합니다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.ai.mock", name = "enabled", havingValue = "true")
public class MockAiClient implements AiClient {

    private static final List<String> CATEGORIES = List.of(
            "지원동기", "직무역량", "프로젝트경험", "문제해결",
            "협업·갈등", "실패·성장", "가치관·인성", "미래계획");

    private static final List<String> STAGES = List.of("stt", "generating", "tts");

    /** 실제 폴링 경로를 태우기 위해 done 전에 processing 을 이만큼 돌려줍니다. */
    private static final int PROCESSING_POLLS = 2;

    private final Map<String, MockTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, MockSession> sessions = new ConcurrentHashMap<>();

    @Override
    public AiSessionStartResponse startSession(AiSessionStartRequest request) {
        String sessionId = "mock_sess_" + UUID.randomUUID().toString().substring(0, 8);
        int total = request.questionCount() != null ? request.questionCount() : 6;

        sessions.put(sessionId, new MockSession(total, request.replayLog() != null));
        String taskId = registerTask(sessionId);

        log.info("[MOCK] 세션 시작 sessionId={} taskId={} questionTotal={}",
                sessionId, taskId, total);
        return new AiSessionStartResponse(sessionId, taskId, total);
    }

    @Override
    public String submitAnswer(String sessionId, AiAnswerSubmitRequest request) {
        if (!sessions.containsKey(sessionId)) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        String taskId = registerTask(sessionId);
        log.info("[MOCK] 답변 접수 sessionId={} questionId={} taskId={}",
                sessionId, request.questionId(), taskId);
        return taskId;
    }

    @Override
    public AiTaskStatusResponse getTask(String taskId) {
        MockTask task = tasks.get(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.INVALID_QUESTION_ID, "존재하지 않는 task_id 입니다");
        }

        int polls = task.polls().incrementAndGet();
        if (polls <= PROCESSING_POLLS) {
            return new AiTaskStatusResponse(
                    AiTaskStatusResponse.STATUS_PROCESSING,
                    STAGES.get(Math.min(polls - 1, STAGES.size() - 1)),
                    null, null, null);
        }
        return new AiTaskStatusResponse(
                AiTaskStatusResponse.STATUS_DONE, null, nextResult(task.sessionId()), null, null);
    }

    @Override
    public List<AiCompany> listCompanies() {
        return List.of(
                new AiCompany("hyundai_enc", "현대건설(주)", "종합건설 · 플랜트"),
                new AiCompany("gs_enc", "GS건설(주)", "종합건설 · 주택"),
                new AiCompany("samsung_cnt", "삼성물산(주)", "종합건설 · 인프라"));
    }

    @Override
    public void abortSession(String sessionId) {
        sessions.remove(sessionId);
        log.info("[MOCK] 세션 중단 sessionId={}", sessionId);
    }

    private String registerTask(String sessionId) {
        String taskId = "mock_task_" + UUID.randomUUID().toString().substring(0, 8);
        tasks.put(taskId, new MockTask(sessionId, new AtomicInteger()));
        return taskId;
    }

    private AiQuestionResult nextResult(String sessionId) {
        MockSession session = sessions.get(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }

        int number = session.delivered().incrementAndGet();
        if (number > session.questionTotal()) {
            return new AiQuestionResult(
                    AiQuestionResult.TYPE_SESSION_END,
                    null, null, null, null, null,
                    null, session.questionTotal(), null, null,
                    false, false, session.questionTotal());
        }

        boolean followup = number % 2 == 0;
        String category = CATEGORIES.get((number - 1) % CATEGORIES.size());
        return new AiQuestionResult(
                followup ? AiQuestionResult.TYPE_FOLLOWUP : AiQuestionResult.TYPE_QUESTION,
                "q_" + number,
                followup
                        ? "방금 말씀하신 부분을 조금 더 구체적으로 설명해 주시겠어요?"
                        : "%s 관련해서 질문드리겠습니다. 준비해 오신 내용을 말씀해 주세요.".formatted(category),
                null,   // 목에서는 TTS 를 만들지 않습니다. TTS_FAILED 와 같은 모양입니다.
                category,
                "L" + (1 + (number - 1) % 3),
                number,
                session.questionTotal(),
                (number - 1) / 2,
                (session.questionTotal() + 1) / 2,
                false,
                session.replay(),
                null);
    }

    private record MockTask(String sessionId, AtomicInteger polls) {
    }

    private record MockSession(int questionTotal, boolean replay, AtomicInteger delivered) {
        MockSession(int questionTotal, boolean replay) {
            this(questionTotal, replay, new AtomicInteger());
        }
    }
}
