package com.cuea.infrastructure.file;

import java.util.UUID;

/**
 * 문서 파일이 어디에 어떻게 저장되는지를 가리는 경계.
 *
 * <p><b>도메인 서비스는 이 인터페이스만 봅니다.</b> 지금 구현은 파일 바이트를
 * Spring 으로 통과시켜 S3 에 올리지만(Issue #28), 동시 업로드가 늘면 프론트가
 * S3 에 직접 올리는 Presigned 방식으로 되돌려야 합니다. 그때 갈아끼울 지점을
 * 한 곳으로 모아두려고 인터페이스를 둡니다.
 */
public interface DocumentFileStorage {

    /**
     * 파일을 저장하고 저장 위치를 가리키는 키를 돌려줍니다.
     *
     * <p>도메인은 이 키가 S3 오브젝트 키인지 로컬 경로인지 알 필요가 없습니다.
     * 그대로 받아 {@code document.object_key} 에 넣고, 나중에 다시 넘기면 됩니다.
     *
     * @param documentPublicId 키에 섞어 충돌을 막습니다. 저장 전에 발급돼 있어야 합니다
     */
    String store(String userId, UUID documentPublicId, UploadedFile file);

    /**
     * 파일 삭제. 두 곳에서 부릅니다.
     *
     * <ul>
     *   <li>저장은 됐는데 뒤 단계가 실패했을 때 되돌리는 보상 경로. <b>여기서 예외를
     *       던지면 원래 실패 원인을 덮습니다.</b></li>
     *   <li>문서 삭제(Issue #38). DB 에서는 이미 숨겼으므로 파일 삭제가 실패해도
     *       사용자 요청을 실패시키지 않습니다.</li>
     * </ul>
     *
     * <p>그래서 구현은 실패를 로그로만 남기고 조용히 끝내야 합니다.
     */
    void remove(String objectKey);
}
