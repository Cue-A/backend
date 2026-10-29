package com.cuea.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 프론트 방향 JSON 은 camelCase 입니다.
 * AI 서버 방향 snake_case 변환은 infrastructure/ai/dto 에서만 합니다.
 *
 * <p>{@link OffsetDateTime} 은 항상 UTC({@code ...Z})로 씁니다. 방금 저장한 엔티티는
 * JVM 시간대({@code +09:00})를, DB 에서 읽은 엔티티는 UTC 를 들고 있어서 그대로 두면
 * 같은 시각이 응답마다 다르게 표기됩니다.
 */
@Configuration
public class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .modules(new JavaTimeModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializerByType(OffsetDateTime.class, new UtcOffsetDateTimeSerializer());
    }

    static class UtcOffsetDateTimeSerializer extends JsonSerializer<OffsetDateTime> {

        @Override
        public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(value.withOffsetSameInstant(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
    }
}
