package com.cuea.domain.user.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.user.entity.Provider;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserEmailServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private UserEmailService userEmailService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        userEmailService = new UserEmailService(userRepository, passwordEncoder);
    }

    @Test
    void LOCAL_인증이_있으면_비밀번호_확인_후_이메일을_바꾼다() {
        User user = User.create("old@example.com", "김취준");
        user.link(Provider.LOCAL, null, "$2a$encoded");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1", "$2a$encoded")).thenReturn(true);
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);

        String result = userEmailService.changeEmail("user-1", "new@example.com", "password1");

        assertThat(result).isEqualTo("new@example.com");
        assertThat(user.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void LOCAL_인증이_없으면_카카오_전용_계정이라_거부한다() {
        User user = User.create("kim@example.com", "김취준");
        user.link(Provider.KAKAO, "kakao-1", null);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userEmailService.changeEmail("user-1", "new@example.com", "password1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMAIL_CHANGE_NOT_ALLOWED);
    }

    @Test
    void 비밀번호가_틀리면_거부한다() {
        User user = User.create("old@example.com", "김취준");
        user.link(Provider.LOCAL, null, "$2a$encoded");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "$2a$encoded")).thenReturn(false);

        assertThatThrownBy(() -> userEmailService.changeEmail("user-1", "new@example.com", "wrong"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        assertThat(user.getEmail()).isEqualTo("old@example.com");
    }

    @Test
    void 이미_다른_사람이_쓰는_이메일이면_거부한다() {
        User user = User.create("old@example.com", "김취준");
        user.link(Provider.LOCAL, null, "$2a$encoded");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1", "$2a$encoded")).thenReturn(true);
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userEmailService.changeEmail("user-1", "taken@example.com", "password1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);
    }

    @Test
    void 자기_자신의_현재_이메일로_바꾸면_중복_검사를_건너뛴다() {
        User user = User.create("same@example.com", "김취준");
        user.link(Provider.LOCAL, null, "$2a$encoded");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1", "$2a$encoded")).thenReturn(true);

        String result = userEmailService.changeEmail("user-1", "same@example.com", "password1");

        assertThat(result).isEqualTo("same@example.com");
    }

    @Test
    void 존재하지_않는_사용자면_거부한다() {
        when(userRepository.findById("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userEmailService.changeEmail("nobody", "new@example.com", "password1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
    }
}
