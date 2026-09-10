package com.cuea.domain.document.entity;

import com.cuea.common.entity.BaseTimeEntity;
import com.cuea.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.UUID;

/**
 * 면접의 근거가 되는 사용자 문서. 포트폴리오·발표자료·대본.
 *
 * <p>{@code docId} 는 내부 조인용이고 <b>외부로 나가는 식별자는 {@code publicId}</b>
 * 입니다. 컨트롤러·DTO 에서 {@code docId} 를 노출하지 마세요. 연속된 정수라
 * 남의 문서 개수와 순서가 그대로 드러납니다.
 *
 * <p>{@code sourceType} 이 어느 컬럼을 읽을지를 정합니다. {@code FILE} 이면
 * {@code objectKey}·{@code fileName}·{@code mimeType}·{@code fileSize}·
 * {@code fileFormat}, {@code MARKDOWN} 이면 {@code docText} 입니다.
 * 양쪽 모두 nullable 인 이유가 이것입니다.
 */
@Entity
@Table(
        name = "document",
        indexes = @Index(name = "idx_document_user", columnList = "user_id")
)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Document extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "doc_id")
    private Long docId;

    /** 외부 노출용 식별자. URL·응답 DTO 에는 이 값만 씁니다. */
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "doc_title", nullable = false, length = 100)
    private String docTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 20)
    private DocType docType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 10)
    private SourceType sourceType;

    /**
     * 원본 파일명. 화면에 그대로 보여줍니다. {@code SourceType.MARKDOWN} 이면 null.
     */
    @Column(name = "file_name", length = 255)
    private String fileName;

    /**
     * S3 오브젝트 키. {@code SourceType.MARKDOWN} 이면 null.
     *
     * <p><b>URL 을 저장하지 않습니다.</b> Presigned URL 은 만료되고, 버킷이나
     * 경로가 바뀌면 저장된 값을 전부 손봐야 합니다. 키만 두고 필요할 때
     * {@code PresignedUrlIssuer} 로 URL 을 만드세요. docs/20-storage.md 참고.
     *
     * <p>ERD 초안의 {@code doc_url varchar(500)} 을 이걸로 바꿨습니다.
     */
    @Column(name = "object_key", columnDefinition = "text")
    private String objectKey;

    /** {@code SourceType.FILE} 일 때만 채웁니다. */
    @Column(name = "mime_type", length = 100)
    private String mimeType;

    /** 바이트 단위. {@code SourceType.FILE} 일 때만 채웁니다. */
    @Column(name = "file_size")
    private Long fileSize;

    /** 사용자가 직접 입력한 본문. {@code SourceType.FILE} 이면 null. */
    @Column(name = "doc_text", columnDefinition = "text")
    private String docText;

    /** {@code SourceType.FILE} 일 때만 채웁니다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "file_format", length = 10)
    private FileFormat fileFormat;

    /**
     * AI 서버의 RAG 인덱스 참조값.
     *
     * <p>AI 파트가 발급한 문자열을 그대로 보관만 합니다. Spring 은 벡터 저장소를
     * 읽지도 쓰지도 않습니다. docs/90-open-questions.md 3번 참고.
     */
    @Column(name = "ai_doc_ref", length = 100)
    private String aiDocRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status;

    public void markParsing() {
        this.status = DocumentStatus.PARSING;
    }

    /** 파싱 완료. AI 가 준 인덱스 참조를 같이 받습니다. */
    public void markReady(String aiDocRef) {
        this.status = DocumentStatus.READY;
        this.aiDocRef = aiDocRef;
    }

    public void markFailed() {
        this.status = DocumentStatus.FAILED;
    }

    /** 세션에 붙일 수 있는 상태인지. */
    public boolean isUsableForSession() {
        return status == DocumentStatus.READY;
    }
}
