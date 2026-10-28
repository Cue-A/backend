package com.cuea.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("API 시각 직렬화")
class JacksonConfigTest {

    private final ObjectMapper objectMapper = mapper();

    private static ObjectMapper mapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jacksonCustomizer().customize(builder);
        return builder.build();
    }

    @Test
    @DisplayName("방금 만든 엔티티의 +09:00 시각도 UTC(Z) 로 나간다")
    void kstIsWrittenAsUtc() throws Exception {
        OffsetDateTime kst = OffsetDateTime.of(2026, 10, 28, 20, 3, 5, 206_785_000, ZoneOffset.ofHours(9));

        String json = objectMapper.writeValueAsString(Map.of("createdAt", kst));

        assertThat(json).isEqualTo("{\"createdAt\":\"2026-10-28T11:03:05.206785Z\"}");
    }

    @Test
    @DisplayName("DB 에서 읽은 UTC 시각과 같은 순간이면 같은 문자열이 나간다")
    void sameInstantSameString() throws Exception {
        OffsetDateTime kst = OffsetDateTime.of(2026, 10, 28, 20, 3, 5, 0, ZoneOffset.ofHours(9));
        OffsetDateTime utc = kst.withOffsetSameInstant(ZoneOffset.UTC);

        assertThat(objectMapper.writeValueAsString(kst))
                .isEqualTo(objectMapper.writeValueAsString(utc))
                .isEqualTo("\"2026-10-28T11:03:05Z\"");
    }

    @Test
    @DisplayName("null 은 그대로 null 이다")
    void nullStaysNull() throws Exception {
        assertThat(objectMapper.writeValueAsString(Map.of())).isEqualTo("{}");
        assertThat(objectMapper.writeValueAsString(new Holder(null))).isEqualTo("{\"at\":null}");
    }

    @Test
    @DisplayName("읽을 때는 오프셋이 있는 문자열을 그대로 받는다")
    void readsOffsetString() throws Exception {
        OffsetDateTime read = objectMapper.readValue("\"2026-10-28T20:03:05+09:00\"", OffsetDateTime.class);

        assertThat(read.toInstant()).isEqualTo(OffsetDateTime.parse("2026-10-28T11:03:05Z").toInstant());
    }

    record Holder(OffsetDateTime at) {
    }
}
