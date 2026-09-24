package com.cuea.domain.document.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.interview.service.InterviewDocumentUsageService;
import com.cuea.infrastructure.file.DocumentFileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 문서 삭제. <b>행은 지우지 않고 숨깁니다</b>(Issue #38).
 *
 * <h2>왜 소프트 삭제인가</h2>
 * {@code session.document_id} 가 NOT NULL 로 문서를 참조합니다. 행을 지우면 FK 에
 * 막히거나 과거 면접 기록이 깨집니다. 삭제된 문서는 목록·상세·등록 상한·면접
 * 시작에서만 빠집니다.
 *
 * <h2>S3 파일은 면접에 안 쓴 문서만 지웁니다</h2>
 * 재연습은 원래 면접의 문서 파일을 AI 에 다시 넘깁니다({@code docs/12-replay.md}).
 * 면접에 쓰인 문서의 파일을 지우면 그 면접은 재연습이 실패하므로 남겨둡니다.
 *
 * <p>숨기기를 먼저 커밋하고 참조를 나중에 봅니다. 순서를 바꾸면 참조를 확인한 뒤
 * 숨기기 전 사이에 새 면접이 이 문서로 시작될 수 있습니다. 지금 순서에서도 숨기기
 * 직전에 문서를 읽어간 면접 시작 요청과는 겹칠 수 있지만, 그 경우 AI 가 파일을
 * 못 읽어 세션 시작이 실패할 뿐 데이터가 깨지지는 않습니다.
 *
 * <h2>왜 트랜잭션이 없나</h2>
 * S3 삭제는 외부 호출이라 트랜잭션 밖에 둡니다. DB 쓰기는 {@link DocumentWriter} 로
 * 넘겨 짧게 끊습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentDeleteService {

    private final DocumentWriter documentWriter;
    private final DocumentFileStorage fileStorage;
    private final InterviewDocumentUsageService documentUsageService;

    public void delete(String userId, String documentId) {
        Document document = documentWriter.markDeleted(parsePublicId(documentId), userId);

        String objectKey = document.getObjectKey();
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        if (documentUsageService.isUsedInAnySession(document.getDocId())) {
            log.info("면접에 쓰인 문서라 S3 파일을 남깁니다 userId={} key={}", userId, objectKey);
            return;
        }
        fileStorage.remove(objectKey);
    }

    /** UUID 가 아닌 값도 없는 문서로 취급합니다. {@code DocumentQueryService} 와 같은 이유입니다. */
    private UUID parsePublicId(String documentId) {
        try {
            return UUID.fromString(documentId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
    }
}
