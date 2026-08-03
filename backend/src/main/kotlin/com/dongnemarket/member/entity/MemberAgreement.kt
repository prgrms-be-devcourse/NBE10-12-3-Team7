package com.dongnemarket.member.entity

import com.dongnemarket.global.common.BaseTimeEntity
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
import java.time.LocalDateTime

/**
 * 회원가입 시 약관/개인정보 동의 이력 한 건. 회원당 동의 항목 수만큼 row가 쌓인다(1:N).
 *
 * 약관 본문은 DB가 아니라 별도 정책 문서로 관리하고, 여기서는 어떤 버전에 언제 동의했는지 증적만 남긴다.
 *
 * [ipAddress]/[userAgent] 만 nullable — DB 컬럼도 nullable 이고(증적이 없을 수 있다),
 * 나머지는 전부 `not null` 컬럼이라 non-null 로 선언한다(V1__baseline.sql).
 */
@Entity
@Table(name = "member_agreements")
class MemberAgreement private constructor(
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "member_id", nullable = false)
    val member: Member,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "agreement_type", nullable = false, length = 30)
    val agreementType: AgreementType,
    @field:Column(nullable = false, length = 20)
    val version: String,
    @field:Column(name = "agreed_at", nullable = false)
    val agreedAt: LocalDateTime,
    @field:Column(name = "ip_address", length = 45)
    val ipAddress: String?,
    @field:Column(name = "user_agent", length = 500)
    val userAgent: String?,
) : BaseTimeEntity() {

    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    companion object {
        @JvmStatic
        fun of(
            member: Member,
            agreementType: AgreementType,
            version: String,
            agreedAt: LocalDateTime,
            ipAddress: String?,
            userAgent: String?,
        ): MemberAgreement = MemberAgreement(member, agreementType, version, agreedAt, ipAddress, userAgent)
    }
}
