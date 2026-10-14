package com.cuea.domain.company.service;

import com.cuea.domain.company.entity.Company;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 선택된 기업의 인재상을 AI 계약이 정한 문자열 형식으로 조립합니다.
 *
 * <p>AI 계약(질문 생성 API 계약 6장 "회사 목록")의 형식은 다음과 같습니다.
 *
 * <pre>
 * 기업명 (업종)
 * 핵심 가치
 *   가치 이름 — 행동지표
 *   가치 이름 — 행동지표
 * </pre>
 *
 * <p>{@code Company.coreValues} 는 {@code [{"name": ..., "indicator": "..."}]} 형태의
 * jsonb 입니다. {@code indicator} 는 배열이 아니라 문장 하나이며, AI 팀이 제공한
 * {@code companies.json} 실 데이터 기준입니다.
 *
 * <p>이 문자열을 만든 뒤에는 {@code AiSessionStartRequest.companyProfileOverride} 에
 * 그대로 담아 보냅니다. 직무 요구역량({@code jobRequirements})은 AI 계약상 선택
 * 사항이며 이번 PR(세션 시작·첫 질문 수신)범위에서는 조립하지 않습니다.
 */
@Component
public class CompanyProfileFormatter {

    private static final String NAME_KEY = "name";
    private static final String INDICATOR_KEY = "indicator";

    /**
     * @param company verified 된 기업. verified=false 기업을 여기 넘기지 마세요.
     *                 노출 제한은 조회 시점({@code CompanyRepository})에서 거릅니다.
     */
    public String format(Company company) {
        StringBuilder sb = new StringBuilder();
        sb.append(company.getCompanyName())
                .append(" (")
                .append(company.getIndustry())
                .append(")\n")
                .append("핵심 가치\n");

        for (String line : coreValueLines(company.getCoreValues())) {
            sb.append("  ").append(line).append('\n');
        }

        return sb.toString().stripTrailing();
    }

    private List<String> coreValueLines(List<Map<String, Object>> coreValues) {
        return coreValues.stream()
                .map(this::formatLine)
                .toList();
    }

    private String formatLine(Map<String, Object> value) {
        Object name = value.get(NAME_KEY);
        Object indicator = value.get(INDICATOR_KEY);
        return "%s — %s".formatted(name, indicator);
    }
}
