package com.cuea.domain.company.controller;

import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.company.dto.response.CompanyInterestResponse;
import com.cuea.domain.company.service.CompanyInterestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 관심 기업 API(Issue #88). <b>전부 로그인 사용자 본인 것만 다룹니다.</b>
 *
 * <p>기업 조회({@link CompanyController})와 같은 {@code /api/companies} 아래지만,
 * 이쪽은 사용자별 쓰기 데이터라 컨트롤러를 나눴습니다. 목록 경로
 * {@code /api/companies/interests} 는 상세 경로 {@code /api/companies/{companyId}}
 * 와 겹치지 않습니다(상세는 숫자 companyId 만 받습니다).
 *
 * <p>등록·해제는 멱등이라 둘 다 200 입니다. 이미 등록된 기업을 또 등록하거나
 * 없던 관심을 해제해도 성공 응답을 돌려줍니다.
 */
@Tag(name = "관심 기업")
@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
public class CompanyInterestController {

    private final CompanyInterestService companyInterestService;

    @Operation(
            summary = "내 관심 기업 목록",
            description = """
                    로그인한 본인의 관심 기업만, 등록 최신순으로 돌려줍니다.

                    각 항목은 companyId 와 name 을 담습니다. 관심 기업이 없으면
                    빈 배열과 함께 200 입니다. 프론트는 이 목록의 companyId 로
                    기업별 관심 여부를 판단합니다.""")
    @GetMapping("/interests")
    public Result<List<CompanyInterestResponse>> list(@CurrentUser String userId) {
        return Result.ok(companyInterestService.list(userId));
    }

    @Operation(
            summary = "관심 기업 등록",
            description = """
                    기업을 관심 목록에 추가합니다. 멱등입니다 — 이미 등록돼 있으면
                    중복 행을 만들지 않고 그대로 200 입니다.

                    검증된(verified=true) 기업만 등록할 수 있습니다. 없는 기업과
                    verified=false 기업은 COMPANY_NOT_FOUND(404) 입니다.""")
    @PostMapping("/{companyId}/interest")
    public Result<Void> register(
            @CurrentUser String userId,
            @PathVariable Long companyId) {

        companyInterestService.register(userId, companyId);
        return Result.ok();
    }

    @Operation(
            summary = "관심 기업 해제",
            description = """
                    기업을 관심 목록에서 제거합니다. 멱등입니다 — 등록하지 않았거나
                    이미 해제한 기업이어도 그대로 200 입니다.

                    본인 관심만 해제되며 다른 사용자의 관심에는 영향이 없습니다.""")
    @DeleteMapping("/{companyId}/interest")
    public Result<Void> remove(
            @CurrentUser String userId,
            @PathVariable Long companyId) {

        companyInterestService.remove(userId, companyId);
        return Result.ok();
    }
}
