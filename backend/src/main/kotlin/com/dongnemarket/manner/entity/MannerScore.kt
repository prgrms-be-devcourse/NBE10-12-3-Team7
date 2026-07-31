package com.dongnemarket.manner.entity

import com.dongnemarket.global.common.BaseTimeEntity
import com.dongnemarket.member.entity.Member
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal

/**
 * 회원별 매너온도 현재값. 회원당 1행(1:1)만 존재하며, 변화 이력은 [MannerScoreHistory]에 별도로 쌓는다.
 *
 * 당근마켓의 매너온도(36.5도 시작)를 벤치마킹하되, 신고 도메인과 양방향으로 연동되도록 설계했다 —
 * 신고가 확정되면 피신고자 온도가 내려가고, 반대로 신고 자체가 무고성으로 판정되면 신고자 온도가 내려간다.
 *
 * `data class` 가 아니라 `class`, JPA 어노테이션은 `@field:` 로 통일 — Report/Favorite 등 다른
 * 전환된 엔티티와 동일한 컨벤션(backend.md). `DEFAULT_SCORE` 등은 `product`(아직 Java) 도메인이
 * `MannerScore.DEFAULT_SCORE` 형태로 직접 참조하므로 `@JvmField` 로 진짜 static 필드를 유지한다.
 */
@Entity
@Table(name = "manner_scores", uniqueConstraints = [UniqueConstraint(name = "uk_manner_scores_member", columnNames = ["member_id"])])
class MannerScore private constructor(
    @field:OneToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "member_id", nullable = false)
    val member: Member,
    initialScore: BigDecimal,
) : BaseTimeEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:Column(nullable = false, precision = 4, scale = 1)
    var score: BigDecimal = initialScore
        protected set

    /**
     * 온도를 delta만큼 변화시키고, [MIN_SCORE]~[MAX_SCORE] 범위로 clamp한 뒤
     * 실제로 반영된 변화량(clamp 이후 실제 변화분)을 반환한다.
     *
     * 실제 반영분을 반환하는 이유: [MannerScoreHistory]에는 "의도한 delta"가 아니라
     * "실제로 적용된 변화량"을 기록해야 이력 합계와 현재 점수가 항상 일치한다.
     */
    fun applyDelta(delta: BigDecimal): BigDecimal {
        val before = score
        var candidate = score.add(delta)
        if (candidate.compareTo(MIN_SCORE) < 0) {
            candidate = MIN_SCORE
        } else if (candidate.compareTo(MAX_SCORE) > 0) {
            candidate = MAX_SCORE
        }
        score = candidate
        return candidate.subtract(before)
    }

    companion object {
        @JvmField
        val DEFAULT_SCORE: BigDecimal = BigDecimal.valueOf(36.5)

        @JvmField
        val MIN_SCORE: BigDecimal = BigDecimal.ZERO

        @JvmField
        val MAX_SCORE: BigDecimal = BigDecimal.valueOf(99.9)

        /** 저신뢰 기준. 기본값(36.5)보다 한참 낮은 값을 저신뢰 임계치로 둔다(관리자 저신뢰 회원 조회·상품 노출 우선순위 하락 등에서 공용으로 참조). */
        @JvmField
        val LOW_TRUST_THRESHOLD: BigDecimal = BigDecimal.valueOf(20.0)

        @JvmStatic
        fun createDefault(member: Member): MannerScore = MannerScore(member, DEFAULT_SCORE)
    }
}
