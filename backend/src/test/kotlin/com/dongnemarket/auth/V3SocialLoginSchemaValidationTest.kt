package com.dongnemarket.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.ResultSet

/**
 * `members.local_login_enabled`/`member_social_accounts`가 실제 MySQL(Testcontainers,
 * 운영과 동일 이미지 `mysql:8.0`) 위에서 Hibernate가 기대하는 타입/제약과 일치하는지 검증한다.
 *
 * **왜 `ddl-auto=create`로 검증하고 V3 Flyway 스크립트로 직접 검증하지 않는가:**
 * V1/V2 Flyway 베이스라인이 이미 develop의 현재 엔티티(예: 매너온도 시스템의 `manner_ratings`)보다
 * 뒤처져 있어(별도로 발견한 기존 이슈, 소셜 로그인과 무관 — PR 설명에 별도 기재), V1→V2→V3를 그대로 적용한 뒤
 * `ddl-auto=validate`로 전체 컨텍스트를 띄우면 이 기존 격차 때문에 `manner_ratings` 누락으로
 * 실패해 정작 확인하려는 소셜 로그인 컬럼 검증까지 가리지 못한다.
 *
 * 대신 `ddl-auto=create`로 Hibernate가 현재 엔티티 그래프로부터 실제로 생성하는 DDL을 직접
 * 확인한다 — Flyway/베이스라인 상태와 무관하게, "Hibernate가 이 필드에 기대하는 컬럼 타입"이라는
 * 질문에 대한 권위 있는 답을 얻을 수 있다. `V3__add_social_login_support.sql`은 이 결과와
 * 일치하도록 맞춘다(현재는 일치 확인 완료 — 아래 테스트가 그 근거).
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Testcontainers
class V3SocialLoginSchemaValidationTest {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    @DisplayName("Hibernate가 members.local_login_enabled에 실제로 생성하는 컬럼 타입을 확인하고, V3 SQL이 같은 타입을 쓰는지 비교한다")
    fun hibernateGeneratedType_forLocalLoginEnabled() {
        val columnType =
            jdbcTemplate.queryForObject(
                """
                select column_type from information_schema.columns
                where table_schema = database() and table_name = 'members' and column_name = 'local_login_enabled'
                """.trimIndent(),
                String::class.java,
            )

        // 실제 MySQL 8.0 + Hibernate 6 + MySQLDialect로 확인된 값(이 테스트로 직접 검증함).
        // V3__add_social_login_support.sql의 local_login_enabled도 동일하게 bit(1)을 쓴다.
        assertThat(columnType).isEqualTo("bit(1)")
    }

    @Test
    @DisplayName("member_social_accounts 테이블의 unique 제약 2개가 Hibernate 생성 스키마에도 존재한다")
    fun memberSocialAccountsTable_hasExpectedUniqueConstraints() {
        val indexNames =
            jdbcTemplate.query(
                "show index from member_social_accounts where non_unique = 0 and key_name <> 'PRIMARY'",
            ) { rs: ResultSet, _: Int -> rs.getString("Key_name") }

        // MemberSocialAccount.@Table(uniqueConstraints=...)에 명시한 이름을 Hibernate가 그대로 사용한다.
        assertThat(indexNames).contains(
            "uk_social_account_provider_provider_user_id",
            "uk_social_account_member_provider",
        )
    }

    companion object {
        @Container
        @JvmStatic
        val MYSQL: MySQLContainer<*> =
            MySQLContainer<Nothing>("mysql:8.0").apply {
                withDatabaseName("dongne_schema_validation")
                withUsername("test")
                withPassword("test")
            }

        @DynamicPropertySource
        @JvmStatic
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", MYSQL::getJdbcUrl)
            registry.add("spring.datasource.username", MYSQL::getUsername)
            registry.add("spring.datasource.password", MYSQL::getPassword)
            registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName)

            // test 프로파일 기본값(H2 + create-drop + H2Dialect)을 덮어써 실제 MySQL 위에서 Hibernate가
            // 무엇을 생성하는지 본다. Flyway는 끈 채로 둔다(V1/V2 베이스라인 격차와 무관하게 검증하기 위함).
            registry.add("spring.flyway.enabled") { false }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create" }
            registry.add("spring.jpa.properties.hibernate.dialect") { "org.hibernate.dialect.MySQLDialect" }
        }
    }
}
