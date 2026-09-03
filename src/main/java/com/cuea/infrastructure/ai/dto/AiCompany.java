package com.cuea.infrastructure.ai.dto;

import java.io.Serializable;

/**
 * GET /ai/companies 의 한 줄. 그대로 프론트에 프록시합니다.
 * DB 에 저장하지 않습니다. 원본이 두 곳에 있으면 반드시 어긋납니다.
 */
@AiJson
public record AiCompany(
        String companyId,
        String name,
        String industry
) implements Serializable {
}
