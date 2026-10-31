package com.cuea.domain.interview.dto.response;

import com.cuea.domain.interview.entity.Folder;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "연습 기록 폴더")
public record FolderResponse(

        @Schema(description = "폴더 ID. 연습 기록 목록의 folderId 필터에 그대로 씁니다")
        String folderId,

        String folderName,

        @Schema(description = "이 폴더의 세션 수. 회차마다 하나로 세고 중단된 세션은 세지 않습니다")
        long sessionCount,

        OffsetDateTime createdAt
) {

    public static FolderResponse of(Folder folder, long sessionCount) {
        return new FolderResponse(folder.getPublicId().toString(), folder.getFolderName(),
                sessionCount, folder.getCreatedAt());
    }
}
