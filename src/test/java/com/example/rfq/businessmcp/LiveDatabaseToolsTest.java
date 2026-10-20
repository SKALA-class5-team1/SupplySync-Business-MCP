/*
 * [테스트] BusinessToolsIntegrationTest와 같은 검증을 .env의 실제 DB로 실행 (SELECT만) + 읽기 전용 확인.
 * 기본 mvn test에서는 건너뛴다. 실행: LIVE_DB=true mvn test -Dtest=LiveDatabaseToolsTest
 */
package com.example.rfq.businessmcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BusinessToolsIntegrationTest와 같은 기대값을 H2 대신 .env의 실제 DB로 확인한다 (SELECT만 실행).
 * 네트워크·실제 데이터에 의존하므로 기본 mvn test에서는 건너뛴다.
 * 실행: LIVE_DB=true mvn test -Dtest=LiveDatabaseToolsTest
 */
@EnabledIfEnvironmentVariable(named = "LIVE_DB", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "internal.api-key=" + McpTestClient.API_KEY,
        "spring.sql.init.mode=never"
})
class LiveDatabaseToolsTest extends BusinessToolsIntegrationTest {
    @Autowired JdbcTemplate jdbc;

    /** DB 계정에 쓰기 권한이 있어도 이 서버의 커넥션은 읽기 전용이어야 한다. 쓰기 시도는 실행 전에 거부된다. */
    @Test
    void connectionIsReadOnly() {
        assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("on");
        assertThatThrownBy(() -> jdbc.update("UPDATE models SET model_name = model_name WHERE 1 = 0"))
                .hasMessageContaining("read-only");
    }
}
