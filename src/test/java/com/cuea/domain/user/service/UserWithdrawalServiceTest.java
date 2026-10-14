package com.cuea.domain.user.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.auth.service.TokenService;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserWithdrawalServiceTest {

    private UserRepository userRepository;
    private TokenService tokenService;
    private UserWithdrawalService userWithdrawalService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tokenService = mock(TokenService.class);
        userWithdrawalService = new UserWithdrawalService(userRepository, tokenService);
    }

    @Test
    void 탈퇴하면_사용자를_지우고_모든_기기의_토큰을_끊는다() {
        User user = User.create("kim@example.com", "김취준");
        when(userRepository.findById(user.getUserId())).thenReturn(Optional.of(user));

        userWithdrawalService.withdraw(user.getUserId());

        verify(userRepository).delete(user);
        verify(tokenService).revokeAll(user.getUserId());
    }

    @Test
    void 존재하지_않는_사용자면_USER_NOT_FOUND_다() {
        when(userRepository.findById("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userWithdrawalService.withdraw("nobody"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
    }
}
