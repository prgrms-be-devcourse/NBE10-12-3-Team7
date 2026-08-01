package com.dongnemarket.manner.entity

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
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal

/**
 * 매너온도 변화 이력(감사로그). 현재값은 [MannerScore] 한 행만 갖고 있어서 "왜 지금 이 점수인지"를
 * 알 수 없는데, 이 테이블이 그 근거를 시간순으로 남긴다. 관리자 모니터링 화면의 타임라인과,
 * 스케줄링 회복 로직의 "최근 30일 감점 이력 없음" 판단 근거로도 쓰인다.
 *
 * 원본 Java는 정적 팩토리가 `of`/`ofReport` 두 개였는데, `relatedReportId` 기본값 `null` +
 * `@JvmOverloads` 하나로 합쳤다 — 두 팩토리 모두 manner 도메인 내부(MannerScoreService)에서만
 * 호출되고 외부 Java 호출부가 없어(grep으로 확인) 안전하게 정리할 수 있었다.
 */
@Entity
@Table(
    name = "manner_score_histories",
    indexes = [
        Index(name = "idx_manner_score_histories_member_id", columnList = "member_id"),
        Index(name = "idx_manner_score_histories_member_reason_created", columnList = "member_id, reason, created_at"),
    ],
)
class MannerScoreHistory private constructor(
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "member_id", nullable = false)
    val member: Member,
    /** 실제로 반영된 변화량(clamp 이후). [MannerScore.applyDelta]의 반환값을 그대로 저장한다. */
    @field:Column(name = "change_amount", nullable = false, precision = 4, scale = 1)
    val changeAmount: BigDecimal,
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 30)
    val reason: MannerScoreChangeReason,
    /** 신고 확정/무고성 페널티일 때만 채워지는 근거 신고 id. 그 외 사유는 null. */
    @field:Column(name = "related_report_id")
    val relatedReportId: Long?,
) : BaseTimeEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(
            member: Member,
            changeAmount: BigDecimal,
            reason: MannerScoreChangeReason,
            relatedReportId: Long? = null,
        ): MannerScoreHistory = MannerScoreHistory(member, changeAmount, reason, relatedReportId)
    }
}
