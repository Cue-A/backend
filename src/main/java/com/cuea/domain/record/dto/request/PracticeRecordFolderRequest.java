package com.cuea.domain.record.dto.request;

import java.util.List;
import java.util.UUID;

/**
 * PATCH /api/practice-records/folder 요청 본문.
 *
 * @param sessionIds 옮길 연습 기록. 1~100개, 중복은 무시
 * @param folderId   넣을 폴더. null 이면 폴더에서 뺍니다. 키가 없어도 null 로 봅니다
 */
public record PracticeRecordFolderRequest(List<String> sessionIds, UUID folderId) {
}
