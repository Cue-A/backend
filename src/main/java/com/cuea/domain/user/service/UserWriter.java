package com.cuea.domain.user.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.interview.service.InterviewSessionQueryService;
import com.cuea.domain.user.entity.User;
import com.cuea.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * DB 쓰기만 담당합니다.
 *
 * <p>{@link UserWithdrawalService} 에서 분리한 이유는 토큰 폐기와 S3 삭제를
 * <b>커밋이 끝난 뒤</b>에 하기 위해서입니다. 트랜잭션 안에서 하면 커밋이 실패했을 때
 * 계정은 남았는데 토큰이나 파일만 사라집니다.
 *
 * <p>세션 ID 수집도 여기서 유저 행 삭제와 같은 트랜잭션으로 묶습니다.
 * {@link UserRepository#findByIdForUpdate} 로 유저 행을 먼저 잠그면, 트랜잭션이
 * 끝날 때까지 이 유저를 참조하는 새 세션이 끼어들 수 없어(FK 삽입에 걸림) 수집한
 * 세션 ID 목록이 삭제 시점의 전체 세션과 어긋나지 않습니다.
 */
@Component
@RequiredArgsConstructor
public class UserWriter {

    private final UserRepository userRepository;
    private final InterviewSessionQueryService sessionQueryService;

    @Transactional
    public List<String> delete(String userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        List<String> sessionIds = sessionQueryService.getSessionIdsOf(userId);
        userRepository.delete(user);
        return sessionIds;
    }
}
