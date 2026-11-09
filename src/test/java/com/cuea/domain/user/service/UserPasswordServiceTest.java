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

class UserPasswordServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private UserPasswordService userPasswordService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        userPasswordService = new UserPasswordService(userRepository, passwordEncoder);
    }

    @Test
    void LOCAL_인증이_있으면_현재_비밀번호_확인_후_바꾼다() {
        User user = User.create("kim@example.com", "김취준");
        user.link(Provider.LOCAL, null, "$2a$old");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("oldpass1", "$2a$old")).thenReturn(true);
        when(passwordEncoder.encode("newpass1")).thenReturn("$2a$new");

        userPasswordService.changePassword("user-1", "oldpass1", "newpass1");

        assertThat(user.authOf(Provider.LOCAL).get().getPassword()).isEqualTo("$2a$new");
    }

    @Test
    void LOCAL_인증이_없으면_카카오_전용_계정이라_거부한다() {
        User user = User.create("kim@example.com", "김취준");
        user.link(Provider.KAKAO, "kakao-1", null);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userPasswordService.changePassword("user-1", "oldpass1", "newpass1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PASSWORD_NOT_SET);
    }

    @Test
    void 현재_비밀번호가_틀리면_거부한다() {
        User user = User.create("kim@example.com", "김취준");
        user.link(Provider.LOCAL, null, "$2a$old");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "$2a$old")).thenReturn(false);

        assertThatThrownBy(() -> userPasswordService.changePassword("user-1", "wrong", "newpass1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        assertThat(user.authOf(Provider.LOCAL).get().getPassword()).isEqualTo("$2a$old");
    }

    @Test
    void 새_비밀번호_형식이_틀리면_거부한다() {
        User user = User.create("kim@example.com", "김취준");
        user.link(Provider.LOCAL, null, "$2a$old");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("oldpass1", "$2a$old")).thenReturn(true);

        assertThatThrownBy(() -> userPasswordService.changePassword("user-1", "oldpass1", "short"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_PASSWORD_FORMAT);
    }

    @Test
    void 존재하지_않는_사용자면_거부한다() {
        when(userRepository.findById("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userPasswordService.changePassword("nobody", "oldpass1", "newpass1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
    }
}
