package com.cuea.domain.interview.service;

import com.cuea.domain.interview.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문서가 면접에 쓰였는지를 다른 도메인에 알려줍니다.
 *
 * <p>문서 삭제가 S3 파일을 지워도 되는지 판단할 때 씁니다. 도메인 간 참조는 서비스
 * 레벨에서만 하므로, 문서 도메인이 {@code InterviewSessionRepository} 를 직접 보지
 * 않도록 여기를 거칩니다. {@code docs/01-conventions.md} 참고.
 */
@Service
@RequiredArgsConstructor
public class InterviewDocumentUsageService {

    private final InterviewSessionRepository sessionRepository;

    /**
     * 상태와 관계없이 이 문서를 참조하는 세션이 있으면 true 입니다. 중단된 세션도
     * 셉니다. 재연습이 이 문서의 파일을 다시 AI 에 넘길 수 있습니다.
     */
    @Transactional(readOnly = true)
    public boolean isUsedInAnySession(Long docId) {
        return sessionRepository.existsByDocument_DocId(docId);
    }
}
