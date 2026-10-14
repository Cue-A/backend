package com.cuea.support;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실제 PostgreSQL 에 붙는 리포지토리 테스트의 부모.
 *
 * <p>UNIQUE 제약이나 조건부 UPDATE 처럼 DB 가 결과를 가르는 분기는 mock 리포지토리로
 * 검증되지 않습니다. docs/01-conventions.md 의 테스트 항목 참고. H2 를 쓰지 않는 이유는
 * {@code jsonb} 컬럼 때문에 스키마가 만들어지지 않아서입니다.
 *
 * <p>Docker 가 없으면 실패하지 않고 건너뜁니다. 나머지 테스트는 여전히 Docker 없이 돕니다.
 * 컨테이너는 클래스마다 새로 띄우지 않도록 static 으로 한 번만 띄웁니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
}
