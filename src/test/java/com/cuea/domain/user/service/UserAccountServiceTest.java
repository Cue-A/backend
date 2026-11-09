package com.cuea.domain.user.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.user.dto.response.AccountResponse;
import com.cuea.domain.user.entity.Provider;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserAccountServiceTest {

    private UserRepository userRepository;
    private UserAccountService userAccountService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userAccountService = new UserAccountService(userRepository);
    }

    @Test
    void 로그인_수단별_연결_정보를_비밀번호_없이_내려준다() {
        User user = User.create("kim@example.com", "김취준");
        user.link(Provider.LOCAL, null, "$2a$encoded");
        user.link(Provider.KAKAO, "kakao-1", null);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

        AccountResponse response = userAccountService.getAccount("user-1");

        assertThat(response.email()).isEqualTo("kim@example.com");
        assertThat(response.auths()).hasSize(2);
        assertThat(response.auths())
                .filteredOn(auth -> auth.provider() == Provider.LOCAL)
                .allMatch(auth -> auth.hasPassword());
        assertThat(response.auths())
                .filteredOn(auth -> auth.provider() == Provider.KAKAO)
                .noneMatch(auth -> auth.hasPassword());
    }

    @Test
    void 존재하지_않는_사용자면_거부한다() {
        when(userRepository.findById("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userAccountService.getAccount("nobody"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
    }
}
