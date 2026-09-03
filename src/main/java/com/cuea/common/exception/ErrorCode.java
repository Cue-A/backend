package com.cuea.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 에러 코드 · HTTP 상태 · 기본 메시지를 함께 정의합니다. */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ── 공통 ────────────────────────────────────────────────
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청이 올바르지 않습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다"),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요"),

    // ── 인증 ────────────────────────────────────────────────
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입된 이메일입니다"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"),

    // ── 문서 · 파일 ──────────────────────────────────────────
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "문서를 찾을 수 없습니다"),
    UNSUPPORTED_FILE_TYPE(HttpStatus.BAD_REQUEST, "지원하지 않는 파일 형식입니다"),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "파일이 너무 큽니다"),
    UPLOAD_NOT_COMPLETED(HttpStatus.BAD_REQUEST, "업로드가 완료되지 않았습니다"),
    STORAGE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "파일 저장소 오류가 발생했습니다"),

    // ── 면접 세션 ────────────────────────────────────────────
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다"),
    SESSION_ENDED(HttpStatus.CONFLICT, "이미 종료된 세션입니다"),
    SESSION_ABORTED(HttpStatus.CONFLICT, "중단된 세션입니다"),
    QUESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "질문을 찾을 수 없습니다"),
    INVALID_QUESTION_COUNT(HttpStatus.BAD_REQUEST, "문항 수는 3, 6, 9 중 하나여야 합니다"),
    INVALID_CATEGORY(HttpStatus.BAD_REQUEST, "허용되지 않은 카테고리입니다"),

    // ── AI 서버 (docs/10-ai-client.md 의 errorCode 와 1:1) ────
    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 서버에 연결할 수 없습니다"),
    AI_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AI 처리 시간이 초과되었습니다"),
    LLM_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "질문 생성에 실패했습니다"),
    STT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "음성 인식에 실패했습니다. 다시 녹음해 주세요"),
    TTS_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "음성 합성에 실패했습니다"),
    INVALID_QUESTION_ID(HttpStatus.BAD_REQUEST, "존재하지 않는 질문 ID 입니다"),
    RESUME_PARSE_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "이력서를 읽지 못했습니다. 다른 파일로 시도해 주세요"),

    // ── 리포트 ──────────────────────────────────────────────
    // 계약 도착 전이라 아직 채우지 않았습니다. docs/13-report.md 참고.
    REPORT_NOT_READY(HttpStatus.ACCEPTED, "리포트를 생성하고 있습니다");

    private final HttpStatus status;
    private final String message;
}
