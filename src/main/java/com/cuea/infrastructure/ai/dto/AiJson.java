package com.cuea.infrastructure.ai.dto;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * AI 서버(Python)와 주고받는 JSON 은 snake_case 입니다.
 * 이 패키지의 DTO 에만 붙이고, 도메인 안으로 snake_case 를 들이지 않습니다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public @interface AiJson {
}
