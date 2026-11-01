package com.cuea.domain.company.service;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.domain.company.dto.response.CompanyInterestResponse;
import com.cuea.domain.company.repository.CompanyRepository;
import com.cuea.domain.company.repository.UserCompanyInterestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 관심 기업 등록·조회·해제(Issue #88). <b>전부 로그인 사용자 본인 것만 다룹니다.</b>
 *
 * <h2>소유권</h2>
 * 조회·삭제는 {@link UserCompanyInterestRepository} 가 쿼리에 {@code userId} 를 묶어
 * 남의 관심이 애초에 나오지도, 지워지지도 않게 합니다. 서비스에서 소유자를 따로
 * 비교하지 않습니다(docs/01-conventions.md).
 *
 * <h2>등록 대상</h2>
 * {@code verified=true} 기업만 등록할 수 있습니다. 없는 기업·verified=false 기업은
 * 상세 조회와 같은 기준으로 {@code COMPANY_NOT_FOUND} 입니다.
 *
 * <h2>멱등</h2>
 * 등록·해제 모두 멱등입니다. 등록은 {@code INSERT ... ON CONFLICT DO NOTHING} 이라
 * 같은 기업을 다시 등록하거나 동시 요청이 몰려도 중복 행 없이 성공합니다. JPA save +
 * {@code try/catch} 로 UNIQUE 예외를 삼키는 방식은 트랜잭션이 rollback-only 로
 * 표시돼 커밋 때 {@code UnexpectedRollbackException}(500)이 나므로 쓰지 않습니다.
 * 해제도 없던 것을 지우면 0 건·성공입니다.
 */
@Service
@RequiredArgsConstructor
public class CompanyInterestService {

    private final UserCompanyInterestRepository interestRepository;
    private final CompanyRepository companyRepository;

    /**
     * 관심 등록. 이미 등록돼 있으면 아무것도 바꾸지 않고 성공입니다(멱등).
     *
     * <p>verified 검증을 먼저 해 없는/미검증 기업을 {@code COMPANY_NOT_FOUND} 로
     * 거른 뒤, ON CONFLICT 로 삽입합니다. 삽입 자체가 FK(company_id·user_id)를
     * 가지므로, 검증을 통과한 companyId 와 토큰에서 온 userId 는 실존합니다.
     */
    @Transactional
    public void register(String userId, Long companyId) {
        boolean verified = companyRepository.findByCompanyIdAndVerifiedTrue(companyId).isPresent();
        if (!verified) {
            throw new BusinessException(ErrorCode.COMPANY_NOT_FOUND);
        }

        interestRepository.insertIfAbsent(userId, companyId);
    }

    /** 내 관심 기업 목록. 등록 최신순. 없으면 빈 목록입니다. */
    @Transactional(readOnly = true)
    public List<CompanyInterestResponse> list(String userId) {
        return interestRepository.findByUser_UserIdOrderByCreatedAtDesc(userId).stream()
                .map(CompanyInterestResponse::from)
                .toList();
    }

    /**
     * 관심 해제. 등록하지 않았거나 이미 해제한 기업이어도 성공입니다(멱등).
     *
     * <p>소유자 조건이 쿼리에 묶여 있어 남의 관심은 지워지지 않습니다. 지운 행 수는
     * 쓰지 않습니다 — 0 이든 1 이든 "그 기업은 더 이상 내 관심이 아니다"라는 결과는
     * 같습니다.
     */
    @Transactional
    public void remove(String userId, Long companyId) {
        interestRepository.deleteByUser_UserIdAndCompany_CompanyId(userId, companyId);
    }
}
