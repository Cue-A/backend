package com.cuea.domain.interview.repository;

/** 폴더별 세션 수. {@link InterviewSessionRepository#countByFolder} 의 결과 한 줄입니다. */
public record FolderSessionCount(Long folderId, Long sessionCount) {
}
