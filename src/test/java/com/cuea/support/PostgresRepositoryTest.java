package com.cuea.support;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실제 PostgreSQL 에 붙는 리포지토리 테스트의 부모.
 *
 * <p>UNIQUE 제약이나 조건부 UPDATE 처럼 DB 가 결과를 가르는 분기는 mock 리포지토리로
 * 검증되지 않습니다. docs/01-conventions.md 의 테스트 항목 참고. H2 를 쓰지 않는 이유는
 * {@code jsonb} 컬럼 때문에 스키마가 만들어지지 않아서입니다.
 *
 * <h2>컨테이너는 싱글턴으로, 정리는 JUnit 에 맡기지 않습니다</h2>
 * 이 부모를 상속하는 리포지토리 테스트들은 설정이 같아 Spring 이 ApplicationContext
 * (와 Hikari 풀)를 캐시해 <b>여러 클래스가 같은 컨텍스트를 재사용</b>합니다. 그런데
 * {@code @Container} 로 컨테이너를 JUnit 에 맡기면 <b>클래스마다</b> 컨테이너가 멈춰,
 * 두 번째 클래스가 멈춘 컨테이너를 가리키는 캐시 컨텍스트를 그대로 써
 * {@code Could not open JPA EntityManager} 로 실패합니다(단독 실행은 통과, 전체 빌드는
 * 실패). 그래서 {@code @Container} 를 쓰지 않고 static 으로 한 번만 띄워 JVM 종료까지
 * 유지합니다(싱글턴). Testcontainers 의 Ryuk 이 JVM 종료 후 정리합니다.
 *
 * <p>{@code @Testcontainers(disabledWithoutDocker = true)} 는 남겨 둡니다. 관리할
 * {@code @Container} 가 없어 생명주기에는 관여하지 않고, <b>Docker 가 없을 때 이 클래스를
 * 통째로 비활성화</b>하는 역할만 합니다. Spring 의 {@code @ServiceConnection} 이미지
 * 해석보다 먼저 클래스가 비활성화돼야 Docker 없는 환경에서 실패가 아니라 건너뜀이 됩니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresRepositoryTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        // 싱글턴: 한 번만 띄우고 멈추지 않습니다. @Container 가 아니므로 JUnit 이
        // 클래스 종료 시 멈추지 않아, 캐시된 Spring 컨텍스트가 같은 컨테이너를 공유합니다.
        POSTGRES.start();
    }
}
