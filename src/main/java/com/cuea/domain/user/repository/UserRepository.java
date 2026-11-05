package com.cuea.domain.user.repository;

import com.cuea.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * 탈퇴용. 이 유저 행을 {@code FOR UPDATE} 로 잡아, 탈퇴 트랜잭션이 끝날 때까지
     * 새 세션이 이 유저를 참조하며 끼어드는 것을 막습니다(세션 FK 삽입은 참조하는
     * 유저 행에 공유 락이 필요해 여기서 막힙니다). 이 락을 쥔 채로 세션 ID 를 모아야
     * 탈퇴 중간에 생긴 세션이 S3 정리 대상에서 빠지지 않습니다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.userId = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") String userId);
}
