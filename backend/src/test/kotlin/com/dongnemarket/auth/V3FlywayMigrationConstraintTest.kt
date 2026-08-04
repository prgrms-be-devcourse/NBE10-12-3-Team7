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

/**
 * `V3__add_social_login_support.sql`이 **실제로 Flyway를 통해 적용된 뒤**, 그 결과 스키마를
 * `information_schema`로 직접 조회해 제약조건을 검증한다.
 *
 * [V3SocialLoginSchemaValidationTest]는 Hibernate가 엔티티로부터 기대하는 타입을 확인하는
 * 테스트라 `ddl-auto=create`를 쓰지만(V1/V2 베이스라인이 develop 최신 엔티티보다 뒤처진 기존
 * 이슈를 피하기 위함 — 별도 보고 대상), 이 테스트는 다르다: Hibernate/JPA를 전혀 관여시키지 않고
 * Flyway가 V1→V2→V3를 그대로 실행한 실제 결과만 SQL로 확인한다. 운영 무결성의 최종 기준은 Flyway SQL
 * 이므로, "Hibernate가 통과시켜주는지"가 아니라 "실제 생성된 DB 제약이 맞는지"를 직접 봐야 한다.
 */
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
    ],
)
@ActiveProfiles("test")
@Tag("integration")
@Testcontainers
class V3FlywayMigrationConstraintTest {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    @DisplayName("members.local_login_enabled: bit(1), NOT NULL, default 없음")
    fun localLoginEnabled_columnDefinition() {
        val row =
            jdbcTemplate.queryForMap(
                """
                select column_type, is_nullable, column_default
                from information_schema.columns
                where table_schema = database() and table_name = 'members' and column_name = 'local_login_enabled'
                """.trimIndent(),
            )

        assertThat(row["column_type"]).isEqualTo("bit(1)")
        assertThat(row["is_nullable"]).isEqualTo("NO")
        assertThat(row["column_default"]).isNull()
    }

    @Test
    @DisplayName("member_social_accounts: UNIQUE(provider, provider_user_id)가 실제로 존재한다")
    fun uniqueConstraint_providerAndProviderUserId() {
        val columns =
            jdbcTemplate.queryForList(
                """
                select column_name from information_schema.statistics
                where table_schema = database() and table_name = 'member_social_accounts'
                  and index_name = 'uk_social_account_provider_provider_user_id'
                order by seq_in_index
                """.trimIndent(),
                String::class.java,
            )

        assertThat(columns).containsExactly("provider", "provider_user_id")
        assertThat(isUniqueIndex("uk_social_account_provider_provider_user_id")).isTrue()
    }

    @Test
    @DisplayName("member_social_accounts: UNIQUE(member_id, provider)가 실제로 존재한다")
    fun uniqueConstraint_memberIdAndProvider() {
        val columns =
            jdbcTemplate.queryForList(
                """
                select column_name from information_schema.statistics
                where table_schema = database() and table_name = 'member_social_accounts'
                  and index_name = 'uk_social_account_member_provider'
                order by seq_in_index
                """.trimIndent(),
                String::class.java,
            )

        assertThat(columns).containsExactly("member_id", "provider")
        assertThat(isUniqueIndex("uk_social_account_member_provider")).isTrue()
    }

    @Test
    @DisplayName("member_social_accounts.member_id는 members.id를 참조하는 FK이며 ON DELETE RESTRICT다")
    fun memberForeignKey_hasRestrictDeleteRule() {
        val row =
            jdbcTemplate.queryForMap(
                """
                select rc.delete_rule, kcu.referenced_table_name, kcu.referenced_column_name
                from information_schema.referential_constraints rc
                join information_schema.key_column_usage kcu
                  on rc.constraint_schema = kcu.constraint_schema
                 and rc.constraint_name = kcu.constraint_name
                where rc.constraint_schema = database()
                  and rc.table_name = 'member_social_accounts'
                  and rc.constraint_name = 'fk_social_account_member'
                """.trimIndent(),
            )

        assertThat(row["delete_rule"]).isEqualTo("RESTRICT")
        assertThat(row["referenced_table_name"]).isEqualTo("members")
        assertThat(row["referenced_column_name"]).isEqualTo("id")
    }

    private fun isUniqueIndex(indexName: String): Boolean {
        val nonUnique =
            jdbcTemplate.queryForObject(
                """
                select distinct non_unique from information_schema.statistics
                where table_schema = database() and table_name = 'member_social_accounts' and index_name = ?
                """.trimIndent(),
                Long::class.javaObjectType,
                indexName,
            )
        return nonUnique != null && nonUnique == 0L
    }

    companion object {
        @Container
        @JvmStatic
        val MYSQL: MySQLContainer<*> =
            MySQLContainer<Nothing>("mysql:8.0").apply {
                withDatabaseName("dongne_flyway_test")
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
        }
    }
}
