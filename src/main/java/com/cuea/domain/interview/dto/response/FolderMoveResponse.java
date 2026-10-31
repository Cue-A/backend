package com.cuea.domain.interview.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "연습 기록 폴더 옮기기 결과")
public record FolderMoveResponse(

        @Schema(description = "옮긴 폴더. 폴더에서 뺐으면 null")
        String folderId,

        @Schema(description = "실제로 옮긴 세션 전부. 같은 면접의 다른 회차까지 들어 있어 보낸 것보다 많을 수 있습니다")
        List<String> movedSessionIds
) {
}
