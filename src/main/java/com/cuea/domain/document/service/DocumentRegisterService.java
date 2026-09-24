package com.cuea.domain.document.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.document.dto.request.DocumentCreateCommand;
import com.cuea.domain.document.dto.response.DocumentResponse;
import com.cuea.domain.document.entity.Document;
import com.cuea.domain.document.entity.DocumentStatus;
import com.cuea.domain.document.entity.FileFormat;
import com.cuea.domain.document.entity.SourceType;
import com.cuea.domain.document.repository.DocumentRepository;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import com.cuea.infrastructure.file.DocumentFileStorage;
import com.cuea.infrastructure.file.FileValidator;
import com.cuea.infrastructure.file.UploadedFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 문서 등록. 파일 업로드와 마크다운 직접 작성을 한 흐름으로 처리합니다.
 *
 * <p><b>저장 방식을 모릅니다.</b> {@link DocumentFileStorage} 뒤에 가려져 있어
 * 파일이 S3 로 가는지 다른 곳으로 가는지 이 클래스는 알지 못합니다. 사용자가
 * 늘어 Presigned 방식으로 되돌릴 때 여기는 그대로 둡니다.
 *
 * <h2>왜 트랜잭션이 없나</h2>
 * 업로드는 외부 호출이라 트랜잭션 안에 두면 그 시간만큼 DB 커넥션을 붙듭니다.
 * DB 쓰기만 {@link DocumentWriter} 로 넘겨 짧게 끊습니다.
 *
 * <h2>인덱싱은 아직 없습니다</h2>
 * 원 설계는 등록 직후 AI 가 RAG 인덱싱을 시작하는 그림이었지만, AI 계약에 문서
 * 인덱싱 엔드포인트가 없습니다(Issue #28). 그래서 등록 즉시 {@code READY} 로
 * 둡니다. 엔드포인트가 생기면 {@link #initialStatus} 를 {@code UPLOADED} 로
 * 바꾸고 업로드 뒤에 인덱싱 호출만 붙이면 됩니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentRegisterService {

    /**
     * 사용자당 문서 상한. 넘으면 오래된 문서를 지우게 안내합니다.
     *
     * <p><b>동시 요청에는 정확하지 않습니다.</b> 개수를 센 뒤 저장하기 때문에, 같은
     * 사용자의 요청 두 개가 겹치면 둘 다 검사를 통과해 21개가 될 수 있습니다.
     * 잠금을 걸지 않은 이유는 넘쳐도 피해가 없고({@code @RateLimit} 20회/분이
     * 완충합니다) 정확한 20 을 보장하려면 사용자 행을 잠가야 해서, 얻는 것보다
     * 비용이 큽니다. 상한이 과금이나 용량과 묶이면 그때 잠금을 검토하세요.
     */
    static final int MAX_DOCUMENTS_PER_USER = 20;

    /** 마크다운 본문 상한. {@code doc_text} 는 TEXT 라 DB 제약이 없어 여기서 막습니다. */
    static final int MAX_MARKDOWN_LENGTH = 20_000;

    /** {@code doc_title VARCHAR(100)}. 넘으면 DB 가 잘라내는 게 아니라 터집니다. */
    static final int MAX_TITLE_LENGTH = 100;

    private static final String MARKDOWN_COPY_FILE_NAME = "content.txt";
    private static final String MARKDOWN_COPY_CONTENT_TYPE = "text/plain; charset=UTF-8";

    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final DocumentFileStorage fileStorage;
    private final FileValidator fileValidator;
    private final DocumentWriter documentWriter;

    public DocumentResponse register(String userId, DocumentCreateCommand command) {
        validateTitle(command.title());
        validateNotOverLimit(userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Document document = command.sourceType() == SourceType.FILE
                ? registerFile(user, command)
                : registerMarkdown(user, command);

        return DocumentResponse.from(document);
    }

    private Document registerFile(User user, DocumentCreateCommand command) {
        UploadedFile file = command.file();
        if (file == null) {
            throw new BusinessException(ErrorCode.INVALID_SOURCE_TYPE,
                    "sourceType=FILE 이면 file 이 필요합니다");
        }

        String extension = fileValidator.validateDocument(
                file.fileName(), file.contentType(), file.size());

        // 저장소 키에 들어가므로 업로드 전에 발급해야 합니다.
        UUID publicId = UUID.randomUUID();
        String objectKey = fileStorage.store(user.getUserId(), publicId, file);

        // 파일은 올라갔는데 DB 저장이 실패하면 버킷에 고아 객체가 남습니다.
        // 이 API 가 3단계를 1단계로 합치면서 없애려던 게 바로 그 고아라서,
        // 여기서 되돌리지 않으면 합친 의미가 없어집니다.
        try {
            return documentWriter.save(Document.ofFile(
                    user,
                    publicId,
                    command.documentType(),
                    command.title().trim(),
                    file.fileName(),
                    objectKey,
                    file.contentType(),
                    file.size(),
                    FileFormat.fromExtension(extension),
                    initialStatus()));
        } catch (RuntimeException e) {
            log.warn("문서 저장 실패. 올라간 파일을 지웁니다 userId={} key={}",
                    user.getUserId(), objectKey);
            fileStorage.remove(objectKey);
            throw e;
        }
    }

    /**
     * 본문은 DB 에 두고, 같은 내용을 {@code .txt} 로 S3 에도 올립니다(Issue #36).
     *
     * <p>면접 시작은 AI 에 파일 URL 을 넘기는 구조라 S3 사본이 없으면 마크다운
     * 문서로 면접을 볼 수 없습니다. {@code docText} 는 편집용 원본으로 남깁니다.
     */
    private Document registerMarkdown(User user, DocumentCreateCommand command) {
        String content = command.content();
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_SOURCE_TYPE,
                    "sourceType=MARKDOWN 이면 content 가 필요합니다");
        }
        if (content.length() > MAX_MARKDOWN_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "본문은 %,d자 이하여야 합니다".formatted(MAX_MARKDOWN_LENGTH));
        }

        UUID publicId = UUID.randomUUID();
        String objectKey = fileStorage.store(user.getUserId(), publicId, markdownCopyOf(content));

        // 파일 문서와 같은 이유로, DB 저장이 실패하면 올린 사본을 지웁니다.
        try {
            return documentWriter.save(Document.ofMarkdown(
                    user,
                    publicId,
                    command.documentType(),
                    command.title().trim(),
                    content,
                    objectKey,
                    initialStatus()));
        } catch (RuntimeException e) {
            log.warn("문서 저장 실패. 올라간 본문 사본을 지웁니다 userId={} key={}",
                    user.getUserId(), objectKey);
            fileStorage.remove(objectKey);
            throw e;
        }
    }

    /**
     * 본문을 AI 가 읽을 {@code .txt} 로 감쌉니다.
     *
     * <p>파일명은 저장소가 확장자를 뽑는 데만 씁니다. 사용자에게 보이지 않고
     * {@code Document.fileName} 에도 남기지 않습니다. charset 을 명시하지 않으면
     * 한글 본문이 깨져 읽힐 수 있습니다.
     */
    private UploadedFile markdownCopyOf(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new UploadedFile(
                MARKDOWN_COPY_FILE_NAME,
                MARKDOWN_COPY_CONTENT_TYPE,
                bytes.length,
                new ByteArrayResource(bytes));
    }

    /**
     * 등록 직후 상태.
     *
     * <p>인덱싱이 없으므로 바로 {@code READY} 입니다. AI 인덱싱 엔드포인트가
     * 생기면 {@code UPLOADED} 로 바꾸세요. 그 한 줄이 프론트에는
     * {@code indexStatus=PROCESSING} 으로 나갑니다.
     */
    private DocumentStatus initialStatus() {
        return DocumentStatus.READY;
    }

    private void validateTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "title 이 필요합니다");
        }
        if (title.trim().length() > MAX_TITLE_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "제목은 %d자 이하여야 합니다".formatted(MAX_TITLE_LENGTH));
        }
    }

    /**
     * 상한 검사를 업로드보다 <b>먼저</b> 합니다. 뒤에 두면 10MB 를 다 받아 S3 에
     * 올린 다음 거절하게 됩니다.
     */
    private void validateNotOverLimit(String userId) {
        if (documentRepository.countByUser_UserId(userId) >= MAX_DOCUMENTS_PER_USER) {
            throw new BusinessException(ErrorCode.DOCUMENT_LIMIT_EXCEEDED);
        }
    }
}
