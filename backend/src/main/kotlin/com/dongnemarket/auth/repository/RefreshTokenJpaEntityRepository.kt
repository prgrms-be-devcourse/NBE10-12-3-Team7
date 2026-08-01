package com.dongnemarket.auth.repository

import com.dongnemarket.auth.entity.RefreshToken
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * 실제 JPA 접근을 담당하는 내부 인터페이스. [JpaRefreshTokenRepository](`test` 프로파일 전용)만
 * 이 인터페이스에 위임한다. 프로파일 제한이 없어 다른 프로파일에서도 빈은 생성되지만, 아무도 주입받지
 * 않으므로 무해하다(Spring Data JPA 프록시 생성 비용만 있고 실제 쿼리는 발생하지 않는다).
 *
 * 파라미터가 nullable `Long?` 인 이유 — non-null `Long` 으로 두면 JVM 시그니처가 primitive `long` 이 되어
 * 원본 `findByMemberId(java.lang.Long)` 과 descriptor 가 달라진다(실제로 `javap` 비교에서 검출했다).
 * 아직 Java 인 호출부에서 자동 언박싱 NPE 로 이어질 수 있어 원본 참조형을 그대로 유지한다.
 */
interface RefreshTokenJpaEntityRepository : JpaRepository<RefreshToken, Long> {
    fun findByMemberId(memberId: Long?): Optional<RefreshToken>

    fun deleteByMemberId(memberId: Long?)
}
