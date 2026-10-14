package com.cuea.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 리포트 생성 설정.
 *
 * <p>{@code app.ai} 가 아니라 따로 둔 이유: {@code AiProperties} 는 레코드라 필드를
 * 늘리면 생성자를 쓰는 곳이 전부 깨집니다. 리포트 값만 여기 모읍니다.
 *
 * @param pollTimeout AI 리포트 작업 폴링 한도. 계약 권장 10분(9문항 약 5분).
 *                    영상 다운로드와 시선 분석이 들어가 질문 생성(60~90초)보다 훨씬 깁니다
 */
@ConfigurationProperties(prefix = "app.report")
public record ReportProperties(Duration pollTimeout) {
}
