package com.cuea.domain.user.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.auth.service.TokenService;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.infrastructure.file.UserFileCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserWithdrawalServiceTest {

    private UserWriter userWriter;
    private TokenService tokenService;
    private InterviewSessionQueryService sessionQueryService;
    private UserFileCleaner fileCleaner;
    private UserWithdrawalService userWithdrawalService;

    @BeforeEach
    void setUp() {
        userWriter = mock(UserWriter.class);
        tokenService = mock(TokenService.class);
        sessionQueryService = mock(InterviewSessionQueryService.class);
        fileCleaner = mock(UserFileCleaner.class);
        userWithdrawalService = new UserWithdrawalService(
                userWriter, tokenService, sessionQueryService, fileCleaner);
    }

    @Test
    void 탈퇴하면_사용자를_지운_뒤에_토큰을_끊고_S3_파일을_지운다() {
        when(sessionQueryService.getSessionIdsOf("user-1")).thenReturn(List.of("s-1", "s-2"));

        userWithdrawalService.withdraw("user-1");

        InOrder order = inOrder(sessionQueryService, userWriter, tokenService, fileCleaner);
        order.verify(sessionQueryService).getSessionIdsOf("user-1");
        order.verify(userWriter).delete("user-1");
        order.verify(tokenService).revokeAll("user-1");
        order.verify(fileCleaner).removeAll("user-1", List.of("s-1", "s-2"));
    }

    @Test
    void DB_삭제가_실패하면_토큰과_파일은_건드리지_않는다() {
        doThrow(new BusinessException(ErrorCode.USER_NOT_FOUND)).when(userWriter).delete("nobody");

        assertThatThrownBy(() -> userWithdrawalService.withdraw("nobody"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(tokenService, never()).revokeAll(anyString());
        verify(fileCleaner, never()).removeAll(anyString(), any());
    }
}
