package com.cuea.domain.report.entity;

/**
 * PARTIAL 리포트의 실패 축 재시도 상태. 리포트 상태({@link ReportStatus})와 따로 둡니다.
 *
 * <p>재시도 중에도 리포트는 PARTIAL 로 계속 보여야 합니다. 화면은 리포트를 그대로 두고 다시
 * 분석하는 축의 칸에서만 로딩을 보여주기 때문입니다. 리포트 상태를 PROCESSING 으로 바꾸면
 * 그동안 상세 조회가 막힙니다.
 *
 * <p>재시도한 적이 없거나 재시도가 성공해 결과가 교체되면 컬럼은 null 입니다.
 */
public enum ReportRetryStatus {

    /** 실패한 축을 다시 분석하는 중. 리포트는 원래 PARTIAL 결과 그대로입니다. */
    PROCESSING,

    /** 마지막 재시도가 실패. 리포트는 원래 PARTIAL 결과 그대로이고 원인은 {@code error_code} 에 있습니다. */
    FAILED
}
