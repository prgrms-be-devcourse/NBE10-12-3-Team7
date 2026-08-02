package com.dongnemarket.auth.repository

import com.dongnemarket.auth.entity.RefreshToken
import java.util.Optional

/**
 * Refresh Token 저장소 추상화. 구현체는 프로파일에 따라 갈린다.
 *
 * `test` → [JpaRefreshTokenRepository](H2, 외부 인프라 불필요)
 *
 * 그 외(dev/prod) → [RedisRefreshTokenRepository](TTL 기반, `auth:refresh:{memberId}`)
 *
 * [com.dongnemarket.auth.service.RefreshTokenService] 는 이 인터페이스에만 의존하므로
 * 저장소를 교체해도 서비스/컨트롤러는 영향받지 않는다.
 */
interface RefreshTokenRepository {
    fun findByMemberId(memberId: Long?): Optional<RefreshToken>

    /** 이미 같은 memberId 의 row/키가 있으면 교체하고, 없으면 새로 만든다(upsert). */
    fun save(refreshToken: RefreshToken?): RefreshToken

    fun deleteByMemberId(memberId: Long?)
}
