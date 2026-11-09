package com.cuea.infrastructure.file;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UserFileCleanerTest {

    private final S3StorageService storage = mock(S3StorageService.class);
    private final UserFileCleaner cleaner = new UserFileCleaner(storage);

    @Test
    void 문서_접두사와_세션별_접두사를_전부_지운다() {
        cleaner.removeAll("user-1", List.of("s-1", "s-2"));

        verify(storage).deleteByPrefix("resumes/user-1/");
        verify(storage).deleteByPrefix("sessions/s-1/");
        verify(storage).deleteByPrefix("sessions/s-2/");
    }

    @Test
    void 한_접두사_삭제가_실패해도_나머지는_계속_지운다() {
        doThrow(new BusinessException(ErrorCode.STORAGE_ERROR))
                .when(storage).deleteByPrefix("resumes/user-1/");

        cleaner.removeAll("user-1", List.of("s-1"));

        verify(storage).deleteByPrefix("sessions/s-1/");
    }
}
