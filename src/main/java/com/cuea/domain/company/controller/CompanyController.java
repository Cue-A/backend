package com.cuea.domain.company.controller;

import com.cuea.common.result.Result;
import com.cuea.common.security.CurrentUser;
import com.cuea.domain.company.dto.response.CompanyDetailResponse;
import com.cuea.domain.company.dto.response.CompanyResponse;
import com.cuea.domain.company.service.CompanyQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 기업 조회 API.
 *
 * <p>면접 옵션 설정 화면(로그인 보호 라우트)의 기업 선택 드롭다운을 채우고(목록),
 * 기업 상세·인재상 화면에 쓸 상세를 돌려줍니다(Issue #87). 기업은 소유자 없는
 * 공용 마스터지만, 이 목록·상세를 쓰는 화면이 로그인 뒤에만 열리므로
 * {@code @CurrentUser} 로 로그인을 요구합니다(userId 로 범위를 좁히지는 않습니다).
 * 질문 생성용 기업 정보 전달은 면접 시작 흐름이 따로 담당합니다.
 *
 * <p>관심 기업 등록·조회·해제는 사용자별 데이터라 {@code CompanyInterestController}
 * 로 분리했습니다(Issue #88).
 */
@Tag(name = "기업")
@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyQueryService companyQueryService;

    @Operation(
            summary = "기업 목록",
            description = """
                    면접 설정에서 고를 수 있는 기업 목록입니다.

                    검증된(verified=true) 기업만, 기업명 오름차순으로 돌려줍니다.
                    요청 파라미터는 없고, 등록된 기업이 없으면 빈 배열과 함께 200 입니다.
                    각 항목은 companyId 와 name 만 담습니다.""")
    @GetMapping
    public Result<List<CompanyResponse>> list(@CurrentUser String userId) {
        return Result.ok(companyQueryService.list());
    }

    @Operation(
            summary = "기업 상세",
            description = """
                    기업 상세·인재상 정보입니다.

                    companyId · name · industry · valuesFormat · coreValues · talentProfile
                    을 담습니다. coreValues 는 { name, indicator } 배열이고,
                    talentProfile 은 없으면 null 입니다.

                    검증된(verified=true) 기업만 조회할 수 있습니다. 없는 기업과
                    verified=false 기업은 모두 COMPANY_NOT_FOUND(404) 입니다.
                    companyId 는 숫자만 받습니다(관심 목록 경로 /interests 와 구분).""")
    @GetMapping("/{companyId:\\d+}")
    public Result<CompanyDetailResponse> detail(
            @CurrentUser String userId,
            @PathVariable Long companyId) {

        return Result.ok(companyQueryService.detail(companyId));
    }
}
