package com.cuea.domain.company.entity;

/**
 * 기업이 인재상을 적어둔 형식.
 *
 * <p>{@code core_values} JSON 을 어떻게 읽고 화면에 뿌릴지가 이 값으로 갈립니다.
 *
 * <p><b>ERD 는 PostgreSQL 네이티브 enum 타입({@code values_format})으로 잡혀
 * 있지만 여기서는 {@code VARCHAR(20)} 에 이름을 저장합니다.</b> {@code ddl-auto: update}
 * 로 돌던 때 Hibernate 가 기존 PG enum 타입에 값을 추가하지 못해 정했고,
 * {@code user_auth.provider} 도 같습니다. 값을 추가하면 {@code company_values_format_check}
 * 제약을 바꾸는 마이그레이션이 필요합니다. docs/02-database.md 의 마이그레이션 절 참고.
 */
public enum ValuesFormat {

    /** 단어형. 예: "도전", "정직" */
    WORD,

    /** 행동원칙형. 예: "고객의 문제부터 정의한다" */
    PRINCIPLE,

    /** 혼합형. 단어와 행동원칙이 섞여 있음 */
    MIXED
}
