package com.dongnemarket.auth.repository

import com.dongnemarket.auth.entity.RefreshToken
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * `test` 프로파일 전용 구현체. 기존 JPA 동작(회원당 1행, 재로그인 시 기존 row 를 dirty checking 으로
 * in-place 교체)을 그대로 유지해 `./gradlew test` 가 외부 Redis 없이 H2 만으로 계속 통과하게 한다.
 *
 * 전환 규칙 — 파라미터는 원본 Java 시그니처의 참조형(`Long`·`RefreshToken`)에 맞춰 nullable 로 둔다.
 * non-null 로 조이면 JVM descriptor 는 같지만 **메서드 진입 시점에 null 검사가 삽입**돼,
 * Java 호출부의 런타임 실패 위치가 원본과 달라진다(`save(null)` 은 원본에서 첫 역참조인
 * `refreshToken.getMemberId()` 에서 NPE 였고 저장소 호출은 0건이었다).
 */
@Repository
@Profile("test")
class JpaRefreshTokenRepository(
    private val jpaRepository: RefreshTokenJpaEntityRepository,
) : RefreshTokenRepository {
    override fun findByMemberId(memberId: Long?): Optional<RefreshToken> = jpaRepository.findByMemberId(memberId)

    /** 기존 row 가 있으면 mutate 해 영속성 컨텍스트 dirty checking 으로 flush 되게 하고, 없으면 새로 저장한다. */
    override fun save(refreshToken: RefreshToken?): RefreshToken =
        jpaRepository
            .findByMemberId(refreshToken!!.memberId)
            .map { existing ->
                existing.replace(refreshToken.token, refreshToken.expiresAt)
                existing
            }.orElseGet { jpaRepository.save(refreshToken) }

    override fun deleteByMemberId(memberId: Long?) {
        jpaRepository.deleteByMemberId(memberId)
    }
}
