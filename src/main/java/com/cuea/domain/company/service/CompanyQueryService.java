package com.cuea.domain.company.service;

import com.cuea.domain.company.dto.response.CompanyResponse;
import com.cuea.domain.company.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 기업 조회. <b>verified=true 기업만, 기업명 오름차순으로</b> 돌려줍니다.
 *
 * <p>기업 데이터는 소유자가 없는 공용 마스터라(docs/02-database.md) 사용자별
 * 스코프가 없습니다. 조회 전용이며 AI 서버를 호출하지 않고 Company 테이블만
 * 직접 봅니다(Redis 캐시·AI 프록시 없음).
 */
@Service
@RequiredArgsConstructor
public class CompanyQueryService {

    private final CompanyRepository companyRepository;

    /** 비어 있으면 빈 목록을 그대로 돌려줍니다. 예외가 아닙니다. */
    @Transactional(readOnly = true)
    public List<CompanyResponse> list() {
        return companyRepository.findByVerifiedTrueOrderByCompanyNameAsc().stream()
                .map(CompanyResponse::from)
                .toList();
    }
}
