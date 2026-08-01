package com.dongnemarket.auth.repository

import com.dongnemarket.auth.entity.MemberSocialAccount
import com.dongnemarket.auth.entity.OAuthProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

/**
 * 전환 규칙 — JPQL 문자열과 `@Param` 이름은 한 글자도 바꾸지 않는다.
 * `@Param` 은 `@Target` 에 PARAMETER 만 있어 use-site target 없이도 파라미터에 붙는다.
 */
interface MemberSocialAccountRepository : JpaRepository<MemberSocialAccount, Long> {
    /** fetch join으로 Member까지 한 번에 가져온다 — 소셜 로그인마다(가장 흔한 경로) 매번 호출되므로 N+1을 피한다. */
    @Query(
        "select msa from MemberSocialAccount msa join fetch msa.member " +
            "where msa.provider = :provider and msa.providerUserId = :providerUserId",
    )
    fun findByProviderAndProviderUserIdFetchMember(
        @Param("provider") provider: OAuthProvider?,
        @Param("providerUserId") providerUserId: String?,
    ): Optional<MemberSocialAccount>
}
