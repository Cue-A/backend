package com.cuea.domain.user.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.user.dto.request.ProfileUpdateRequest;
import com.cuea.domain.user.dto.response.ProfileResponse;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.entity.UserProfile;
import com.cuea.domain.user.repository.UserProfileRepository;
import com.cuea.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserProfileServiceTest {

    private UserRepository userRepository;
    private UserProfileRepository userProfileRepository;
    private UserProfileService userProfileService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userProfileRepository = mock(UserProfileRepository.class);
        userProfileService = new UserProfileService(userRepository, userProfileRepository);
    }

    @Test
    void user_profile_행이_없으면_선택_필드가_전부_null이다() {
        User user = User.create("kim@example.com", "김취준");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(userProfileRepository.findByUser_UserId("user-1")).thenReturn(Optional.empty());

        ProfileResponse response = userProfileService.getProfile("user-1");

        assertThat(response.userName()).isEqualTo("김취준");
        assertThat(response.introduction()).isNull();
        assertThat(response.careerLevel()).isNull();
    }

    @Test
    void 빈_요청이면_거부한다() {
        ProfileUpdateRequest empty = new ProfileUpdateRequest(null, null, null, null, null, null, null);

        assertThatThrownBy(() -> userProfileService.updateProfile("user-1", empty))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void 허용되지_않은_careerLevel이면_거부한다() {
        ProfileUpdateRequest request =
                new ProfileUpdateRequest(null, null, null, "senior", null, null, null);

        assertThatThrownBy(() -> userProfileService.updateProfile("user-1", request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void user_profile_행이_없으면_최초_수정에서_새로_만든다() {
        User user = User.create("kim@example.com", "김취준");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(userProfileRepository.findByUser_UserId("user-1")).thenReturn(Optional.empty());
        when(userProfileRepository.save(any(UserProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProfileUpdateRequest request =
                new ProfileUpdateRequest(null, "안녕하세요", null, "entry", null, null, null);

        ProfileResponse response = userProfileService.updateProfile("user-1", request);

        assertThat(response.introduction()).isEqualTo("안녕하세요");
        assertThat(response.careerLevel()).isEqualTo("entry");
    }

    @Test
    void userName을_보내면_User_닉네임도_바뀐다() {
        User user = User.create("kim@example.com", "김취준");
        UserProfile profile = UserProfile.builder().user(user).build();
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(userProfileRepository.findByUser_UserId("user-1")).thenReturn(Optional.of(profile));

        ProfileUpdateRequest request =
                new ProfileUpdateRequest("새이름", null, null, null, null, null, null);

        ProfileResponse response = userProfileService.updateProfile("user-1", request);

        assertThat(response.userName()).isEqualTo("새이름");
        assertThat(user.getNickname()).isEqualTo("새이름");
    }

    @Test
    void 존재하지_않는_사용자면_거부한다() {
        when(userRepository.findById("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userProfileService.getProfile("nobody"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
    }
}
