package com.dongnemarket.auth.entity

import com.dongnemarket.global.common.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 이메일의 "인증 완료" 여부만 담는다. 인증 코드 자체(발급·비교·쿨다운·만료)는 TTL 데이터라 Redis
 * ([com.dongnemarket.auth.repository.EmailVerificationCodeRepository])가 담당하고, 여기서는 관리하지 않는다.
 *
 * 인증 완료 상태는 코드의 5분 TTL 과 무관하게 회원가입 시점까지 유지돼야 하므로(인증 후 한참 뒤에
 * 가입해도 통과) TTL 저장소가 아닌 DB 에 남긴다(`AuthService.signup` 의 `existsByEmailAndVerifiedTrue` 참고).
 *
 * 전환 규칙 — `docs/kotlin-migration/auth-migration-notes.md` 「영속성 entity」절 참고.
 * - [verified] 는 **프로퍼티 이름과 getter 이름이 서로 달라야 하는 유일한 자리**다.
 *   - 프로퍼티 이름은 `verified` 여야 한다 — `EmailVerificationRepository.existsByEmailAndVerifiedTrue` 가
 *     Spring Data 파생 쿼리라 엔티티 속성명 `verified` 로 해석된다. `isVerified` 로 바꾸면 기동 시점에 깨진다.
 *   - getter 이름은 `isVerified()` 여야 한다 — Java 테스트(`EmailVerificationServiceTest`)가 그대로 호출한다.
 *   그래서 `@get:JvmName` 을 쓰고, Kotlin 이 `@JvmName` 을 open 멤버에 금지하므로 이 프로퍼티만 `final` 이다.
 *   이 엔티티를 지연 로딩 프록시로 받는 연관관계가 없어 finality 로 인한 문제는 발생하지 않는다.
 */
@Entity
@Table(name = "email_verifications")
class EmailVerification protected constructor() : BaseTimeEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:Column(nullable = false, unique = true, length = 100)
    var email: String? = null
        protected set

    @get:JvmName("isVerified")
    @field:Column(nullable = false)
    final var verified: Boolean = false
        protected set

    @field:Column(name = "verified_at")
    var verifiedAt: LocalDateTime? = null
        protected set

    private constructor(email: String?) : this() {
        this.email = email
    }

    /** 인증 완료로 표시 */
    fun verify(now: LocalDateTime?) {
        this.verified = true
        this.verifiedAt = now
    }

    /** 같은 이메일로 새 인증 코드를 재요청하면, 이전 인증 상태를 무효화한다(새 코드에 대해 다시 인증해야 함). */
    fun unverify() {
        this.verified = false
        this.verifiedAt = null
    }

    companion object {
        /** 새 인증 코드로 인증에 성공했을 때 최초 발급 */
        @JvmStatic
        fun verified(
            email: String?,
            verifiedAt: LocalDateTime?,
        ): EmailVerification {
            val verification = EmailVerification(email)
            verification.verify(verifiedAt)
            return verification
        }
    }
}
