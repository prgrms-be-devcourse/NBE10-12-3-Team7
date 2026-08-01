package com.dongnemarket.auth.entity

import com.dongnemarket.global.common.BaseTimeEntity
import com.dongnemarket.member.entity.Member
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/**
 * 회원과 소셜 로그인 제공자 계정의 연동 정보. 한 회원이 provider별로 최대 1개까지 연동할 수 있다
 * (동일 provider 중복 연동은 DB unique 제약으로 차단). 연동 시각은 [BaseTimeEntity.createdAt] 로 갈음한다.
 *
 * unique 제약을 엔티티에도 선언해 dev(`ddl-auto=update`)와 prod(Flyway, `V3__add_social_login_support.sql`)
 * 양쪽에서 동일하게 DB 레벨로 강제되도록 한다 — 한쪽에만 있으면 동시성 처리(UNIQUE 위반 재조회)가
 * 환경별로 다르게 동작한다.
 *
 * 전환 규칙 — `docs/kotlin-migration/auth-migration-notes.md` 「영속성 entity」절 참고.
 * [member] 는 `LAZY` 연관이라 프록시 대상이다. allOpen 이 클래스와 프로퍼티를 open 으로 유지하므로
 * getter 를 가로챌 수 있다 — 이 엔티티에는 `@JvmName` 을 쓰지 않는다(썼다면 final 이 되어 프록시가 깨진다).
 */
@Entity
@Table(
    name = "member_social_accounts",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_social_account_provider_provider_user_id",
            columnNames = ["provider", "provider_user_id"],
        ),
        UniqueConstraint(
            name = "uk_social_account_member_provider",
            columnNames = ["member_id", "provider"],
        ),
    ],
)
class MemberSocialAccount protected constructor() : BaseTimeEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "member_id", nullable = false)
    var member: Member? = null
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 20)
    var provider: OAuthProvider? = null
        protected set

    @field:Column(name = "provider_user_id", nullable = false, length = 255)
    var providerUserId: String? = null
        protected set

    private constructor(
        member: Member?,
        provider: OAuthProvider?,
        providerUserId: String?,
    ) : this() {
        this.member = member
        this.provider = provider
        this.providerUserId = providerUserId
    }

    companion object {
        @JvmStatic
        fun of(
            member: Member?,
            provider: OAuthProvider?,
            providerUserId: String?,
        ): MemberSocialAccount = MemberSocialAccount(member, provider, providerUserId)
    }
}
