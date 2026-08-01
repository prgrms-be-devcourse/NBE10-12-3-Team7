package com.dongnemarket.auth.repository

import java.time.Duration
import java.util.Optional

/**
 * 비밀번호 재설정 토큰(TTL 데이터) 저장소 추상화. 구현체는 프로파일에 따라 갈린다.
 *
 * `test` → [InMemoryPasswordResetTokenRepository](외부 인프라 불필요)
 *
 * 그 외(dev/prod) → [RedisPasswordResetTokenRepository](TTL 기반, 2-key 구조)
 *
 * 재설정 링크에는 원문 토큰만 담기고 email 은 없어(confirm 시 tokenHash 로만 회원을 찾음),
 * member→tokenHash 와 tokenHash→memberId 양방향 키가 모두 필요하다: 전자는 재요청 쿨다운/교체 판단용,
 * 후자는 confirm 조회용.
 */
interface PasswordResetTokenRepository {
    /** memberId→tokenHash, tokenHash→memberId 두 키를 동일한 TTL 로 함께 저장한다. */
    fun save(
        memberId: Long?,
        tokenHash: String?,
        ttl: Duration?,
    )

    /** 재요청 쿨다운/교체 판단에 쓰인다. */
    fun findTokenHashByMemberId(memberId: Long?): Optional<String>

    fun getRemainingTtlByMemberId(memberId: Long?): Optional<Duration>

    /** confirm 시 원문 토큰의 해시로 회원을 찾는다. */
    fun findMemberIdByTokenHash(tokenHash: String?): Optional<Long>

    fun deleteByMemberId(memberId: Long?)

    fun deleteByTokenHash(tokenHash: String?)
}
