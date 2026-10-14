package com.cuea.domain.document.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.document.entity.Document;
import com.cuea.infrastructure.file.DocumentFileStorage;
import lombok.RequiredArgsConstructor;
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
 * <h2>S3 파일은 항상 지웁니다</h2>
 * 사용자는 문서를 지우면 파일도 사라진다고 기대합니다. 면접에 쓰인 문서라도
 * 남기지 않습니다. 그래서 <b>삭제된 문서로 본 면접은 재연습할 수 없습니다.</b>
 * 재연습은 원래 면접의 문서 파일을 AI 에 다시 넘기는데({@code docs/12-replay.md})
 * 그 파일이 없기 때문입니다. 재연습 쪽은 {@link Document#isDeleted()} 로 먼저
 * 거절해야 합니다. 과거 면접 기록과 리포트는 행이 남아 있어 그대로 보입니다.
 *
 * <h2>왜 트랜잭션이 없나</h2>
 * S3 삭제는 외부 호출이라 트랜잭션 밖에 둡니다. DB 쓰기는 {@link DocumentWriter} 로
 * 넘겨 짧게 끊습니다.
 */
@Service
@RequiredArgsConstructor
public class DocumentDeleteService {

    private final DocumentWriter documentWriter;
    private final DocumentFileStorage fileStorage;

    public void delete(String userId, String documentId) {
        Document document = documentWriter.markDeleted(parsePublicId(documentId), userId);

        String objectKey = document.getObjectKey();
        if (objectKey != null && !objectKey.isBlank()) {
            fileStorage.remove(objectKey);
        }
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
