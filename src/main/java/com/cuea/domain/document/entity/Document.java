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
 * 면접의 근거가 되는 사용자 문서. 자기소개서·포트폴리오.
 *
 * <p>{@code docId} 는 내부 조인용이고 <b>외부로 나가는 식별자는 {@code publicId}</b>
 * 입니다. 컨트롤러·DTO 에서 {@code docId} 를 노출하지 마세요. 연속된 정수라
 * 남의 문서 개수와 순서가 그대로 드러납니다.
 *
 * <p>{@code sourceType} 은 <b>원본이 어디서 왔는가</b>입니다. {@code FILE} 은 사용자가
 * 올린 파일이라 {@code fileName}·{@code mimeType}·{@code fileSize}·{@code fileFormat}
 * 을 채우고, {@code MARKDOWN} 은 직접 작성한 본문이라 {@code docText} 를 채웁니다.
 * 양쪽 모두 nullable 인 이유가 이것입니다.
 *
 * <p>{@code objectKey} 는 <b>둘 다 가집니다</b>(Issue #36). 면접 시작은 AI 에 파일
 * URL 을 넘기는 구조라, 마크다운도 본문을 {@code .txt} 로 S3 에 올려둡니다. 그래서
 * "면접에 쓸 수 있는가" 는 {@code sourceType} 이 아니라 {@code objectKey} 로 판단합니다.
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
     * S3 오브젝트 키. {@code FILE} 은 올린 원본, {@code MARKDOWN} 은 본문의 {@code .txt} 사본입니다.
     *
     * <p>Issue #36 이전에 등록된 마크다운 문서는 null 입니다. 이 행들은 면접에 쓸 수
     * 없고 다시 등록해야 합니다.
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

    /**
     * 사용자가 직접 입력한 본문. {@code SourceType.FILE} 이면 null.
     *
     * <p><b>원본은 이쪽입니다.</b> S3 의 {@code .txt} 는 AI 가 읽어가는 사본입니다.
     * 본문 수정 기능을 만들 때는 같은 {@code objectKey} 에 사본을 다시 올려야 두 곳이
     * 어긋나지 않습니다.
     */
    @Column(name = "doc_text", columnDefinition = "text")
    private String docText;

    /** {@code SourceType.FILE} 일 때만 채웁니다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "file_format", length = 10)
    private FileFormat fileFormat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status;

    /**
     * 파일로 등록된 문서.
     *
     * <p>{@code publicId} 를 <b>밖에서 받습니다.</b> 저장소 키에 이 값이 들어가므로
     * 엔티티를 만들기 전에 이미 발급돼 있어야 합니다. 여기서 새로 만들면 파일이
     * 올라간 위치와 문서가 가리키는 위치가 달라집니다.
     */
    public static Document ofFile(User user,
                                  UUID publicId,
                                  DocType docType,
                                  String title,
                                  String fileName,
                                  String objectKey,
                                  String mimeType,
                                  long fileSize,
                                  FileFormat fileFormat,
                                  DocumentStatus status) {
        return Document.builder()
                .user(user)
                .publicId(publicId)
                .docType(docType)
                .docTitle(title)
                .sourceType(SourceType.FILE)
                .fileName(fileName)
                .objectKey(objectKey)
                .mimeType(mimeType)
                .fileSize(fileSize)
                .fileFormat(fileFormat)
                .status(status)
                .build();
    }

    /**
     * 사용자가 직접 작성한 문서.
     *
     * <p>{@code objectKey} 는 본문을 올린 {@code .txt} 사본의 위치입니다. 원본 파일이
     * 없으므로 {@code fileName}·{@code mimeType}·{@code fileSize}·{@code fileFormat} 은
     * 비워둡니다.
     */
    public static Document ofMarkdown(User user,
                                      UUID publicId,
                                      DocType docType,
                                      String title,
                                      String docText,
                                      String objectKey,
                                      DocumentStatus status) {
        return Document.builder()
                .user(user)
                .publicId(publicId)
                .docType(docType)
                .docTitle(title)
                .sourceType(SourceType.MARKDOWN)
                .docText(docText)
                .objectKey(objectKey)
                .status(status)
                .build();
    }

    /** 프론트에 나가는 인덱싱 상태. 내부 단계를 그대로 노출하지 않습니다. */
    public IndexStatus indexStatus() {
        return status.toIndexStatus();
    }

    public void markParsing() {
        this.status = DocumentStatus.PARSING;
    }

    /** 파싱 완료. */
    public void markReady() {
        this.status = DocumentStatus.READY;
    }

    public void markFailed() {
        this.status = DocumentStatus.FAILED;
    }

    /** 세션에 붙일 수 있는 상태인지. */
    public boolean isUsableForSession() {
        return status == DocumentStatus.READY;
    }

    /** AI 가 읽어갈 파일이 S3 에 있는지. 출처({@code sourceType})와 무관합니다. */
    public boolean hasStoredFile() {
        return objectKey != null && !objectKey.isBlank();
    }
}
