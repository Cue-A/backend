package com.cuea;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Cue&A 백엔드.
 *
 * <p>서비스명은 Cue&A 지만 {@code &} 는 Java 식별자에 쓸 수 없어
 * 코드에서는 {@code com.cuea} / {@code CueAApplication} 을 씁니다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CueAApplication {

    public static void main(String[] args) {
        SpringApplication.run(CueAApplication.class, args);
    }
}
