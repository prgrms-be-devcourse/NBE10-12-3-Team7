package com.dongnemarket.auth.repository

import com.dongnemarket.auth.entity.RefreshToken
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * `test` 프로파일 전용 구현체. 기존 JPA 동작(회원당 1행, 재로그인 시 기존 row 를 dirty checking 으로
 * in-place 교체)을 그대로 유지해 `./gradlew test` 가 외부 Redis 없이 H2 만으로 계속 통과하게 한다.
 *
 * 전환 규칙 — 구현 대상인 [RefreshTokenRepository] 는 아직 Java 인터페이스다(3단계 대상).
 * 파라미터를 platform type 그대로 두지 않고 원본 Java 시그니처(`Long`·`RefreshToken` 참조형)에 맞춰
 * nullable 로 선언한다. non-null 로 조이면 Kotlin 이 런타임 null 검사를 삽입해, 아직 Java 인 호출부가
 * 컴파일 경고 없이 런타임에만 깨진다(1단계 `oidcNonce` 사례와 같은 함정).
 */
@Repository
@Profile("test")
class JpaRefreshTokenRepository(
    private val jpaRepository: RefreshTokenJpaEntityRepository,
) : RefreshTokenRepository {
    override fun findByMemberId(memberId: Long?): Optional<RefreshToken> = jpaRepository.findByMemberId(memberId)

    /** 기존 row 가 있으면 mutate 해 영속성 컨텍스트 dirty checking 으로 flush 되게 하고, 없으면 새로 저장한다. */
    override fun save(refreshToken: RefreshToken): RefreshToken =
        jpaRepository
            .findByMemberId(refreshToken.memberId)
            .map { existing ->
                existing.replace(refreshToken.token, refreshToken.expiresAt)
                existing
            }.orElseGet { jpaRepository.save(refreshToken) }

    override fun deleteByMemberId(memberId: Long?) {
        jpaRepository.deleteByMemberId(memberId)
    }
}
