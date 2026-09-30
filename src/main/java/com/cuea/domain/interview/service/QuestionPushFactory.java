package com.cuea.domain.interview.service;

import com.cuea.domain.interview.entity.Question;
import com.cuea.infrastructure.file.ObjectKeys;
import com.cuea.infrastructure.file.PresignedUrlIssuer;
import com.cuea.infrastructure.websocket.message.QuestionPushMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 저장된 {@link Question} 을 프론트로 밀어줄 {@link QuestionPushMessage} 로 만드는
 * 공용 팩토리. 세션 시작({@link InterviewFirstQuestionPoller})과 답변 진행
 * ({@link InterviewAnswerPoller}) 양쪽이 <b>동일한 규칙</b>으로 질문을 push 하도록
 * 조립 책임을 여기 모았습니다.
 *
 * <h2>질문 음성 presigned GET (Issue #41)</h2>
 * <p>AI 는 질문 TTS 음성을 private 버킷의
 * {@code sessions/{sessionId}/questions/{questionId}.mp3} 에 직접 올리고, task 결과의
 * {@code audio_url} 로 <b>서명 없는</b> S3 URL 을 돌려줍니다. 버킷이 private 이면
 * 프론트가 그 URL 을 그대로 GET 하면 403 이 납니다. 그래서 push 시점에 같은 object 에
 * 대한 <b>presigned GET URL</b> 을 발급해 내려줍니다.
 *
 * <ul>
 *   <li><b>음성 있음</b>({@code audioUrl != null}) — AI 가 준 URL 을 parsing 하지 않고,
 *       계약상 고정된 key 규칙({@link ObjectKeys#questionAudio})으로 object key 를 만들어
 *       presign 합니다. 엔드포인트·custom domain·path-style/virtual-hosted 차이로 URL
 *       parsing 이 취약하기 때문입니다. 프론트에는 서명 URL 을 내려주고
 *       {@code audioAvailable=true}.</li>
 *   <li><b>음성 없음</b>({@code audioUrl == null}, TTS_FAILED) — presign 을 <b>시도하지
 *       않고</b> {@code audioUrl=null}, {@code audioAvailable=false} 로 내려 텍스트만으로
 *       진행합니다. 로컬/목 환경도 이 경로를 탑니다(목은 음성을 만들지 않음).</li>
 * </ul>
 *
 * <h2>presign 실패 = text-only fallback (Issue #41)</h2>
 * <p>질문 <b>text 는 이미 정상 생성·저장</b>된 상태이고 음성은 optional presentation
 * layer 입니다. 그래서 presigned GET 발급이 실패해도 세션을 중단하지 않고, 해당 질문을
 * TTS_FAILED 와 같은 모양({@code audioUrl=null}, {@code audioAvailable=false})으로
 * 텍스트만 push 합니다. 이는 기존 TTS 실패(text-only) 정책과 동일하며, presign 실패
 * 하나로 전체 면접이 종료되지 않아 MVP/데모 안정성에 유리합니다.
 *
 * <p><b>AWS SDK 예외 처리는 인프라 계층({@link PresignedUrlIssuer})에 있습니다.</b>
 * 도메인인 이 팩토리는 AWS SDK 타입을 알지 못하며,
 * {@link PresignedUrlIssuer#issueQuestionAudioDownload(String)} 가 돌려주는
 * {@link java.util.Optional} 만 봅니다. 값이 있으면 서명 URL, 비어 있으면(음성 없음 또는
 * presign 실패) text-only 로 진행합니다. "URL 을 얻지 못하면 세션을 유지한 채 text-only
 * 로 계속한다"는 <b>면접 도메인 정책</b>만 여기 남고, "SdkException 을 감지·로깅한다"는
 * <b>인프라 관심사</b>는 {@link PresignedUrlIssuer} 가 캡슐화합니다.
 *
 * <p><b>DB 에는 presigned URL 을 저장하지 않습니다.</b> presign 은 만료되므로 push
 * 시점에만 만듭니다. {@link Question#getAudioUrl()} 은 AI 가 준 안정적인 값(음성 존재
 * 여부 판정용)으로 그대로 두고, 저장 스키마는 바꾸지 않습니다.
 */
@Component
@RequiredArgsConstructor
public class QuestionPushFactory {

    private final PresignedUrlIssuer presignedUrlIssuer;

    /**
     * 저장된 질문을 push 메시지로 만듭니다. 음성이 있으면 presigned GET URL 을 담고,
     * presign 이 실패하거나 음성이 없으면 {@code audioUrl=null}·{@code audioAvailable=false}
     * 로 텍스트만 내려줍니다(text-only fallback).
     *
     * @param question      저장된 질문(수신 즉시 저장 후 전달)
     * @param questionTotal 진행률 표시용 총 문항 수
     */
    public QuestionPushMessage create(Question question, Integer questionTotal) {
        String presignedAudioUrl = presignedQuestionAudio(question);
        boolean audioAvailable = presignedAudioUrl != null;

        return new QuestionPushMessage(
                question.getQuestionId(),
                question.getType().name(),
                question.getText(),
                presignedAudioUrl,
                audioAvailable,
                question.getCategory(),
                question.getDifficulty(),
                question.getQuestionNumber(),
                questionTotal
        );
    }

    /**
     * 음성이 있으면 계약 key 로 presigned GET 을 요청합니다. 음성이 없으면(TTS 실패)
     * presign 을 호출하지 않고 {@code null}, presign 발급이 실패하면 인프라가
     * {@link java.util.Optional#empty()} 를 돌려주므로 여기서도 {@code null}
     * (text-only fallback). AWS SDK 예외는 이 도메인 코드가 알지 못하며 인프라가
     * 캡슐화·로깅합니다(중복 로그를 남기지 않습니다).
     */
    private String presignedQuestionAudio(Question question) {
        if (question.getAudioUrl() == null) {
            return null;
        }
        return presignedUrlIssuer.issueQuestionAudioDownload(
                        ObjectKeys.questionAudio(question.getSessionId(), question.getQuestionId()))
                .orElse(null);
    }
}
