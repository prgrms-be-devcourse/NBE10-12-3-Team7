package com.dongnemarket.report.entity

import com.dongnemarket.global.common.BaseTimeEntity
import com.dongnemarket.member.entity.Member
import com.dongnemarket.product.entity.Product
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
import jakarta.persistence.UniqueConstraint

/**
 * `data class` 가 아니라 `class` — equals/hashCode 가 지연 로딩·JPA 동일성과 어긋난다.
 * JPA·검증 어노테이션은 `@field:` 로 백킹 필드에 통일해 붙인다.
 *
 * targetMember/targetProduct 는 정확히 하나만 채워진다(reportType 이 어느 쪽인지 결정) — 이 불변식은
 * private 생성자 + [ofProduct]/[ofMember] 팩토리로만 강제된다. [resolveTargetMemberId] 등이
 * 그 불변식을 그대로 신뢰해 `!!` 로 읽는다(원본 Java 도 null 체크 없이 그대로 역참조했다).
 */
@Entity
@Table(
    name = "reports",
    indexes = [
        Index(name = "idx_reports_reporter_id", columnList = "reporter_id"),
        Index(name = "idx_reports_target_product_id", columnList = "target_product_id"),
        Index(name = "idx_reports_target_member_id", columnList = "target_member_id"),
    ],
    uniqueConstraints = [
        // 동시 요청(레이스 컨디션)으로 애플리케이션 레벨의 existsBy... 검증을 함께 통과해도
        // DB 유니크 제약이 최종적으로 중복 신고 저장을 막는다. NULL 컬럼은 MySQL에서 유니크 검사 대상이 아니므로
        // (상품 신고는 target_member_id가, 회원 신고는 target_product_id가 항상 NULL) 서로 간섭하지 않는다.
        UniqueConstraint(name = "uk_reports_reporter_target_product", columnNames = ["reporter_id", "target_product_id"]),
        UniqueConstraint(name = "uk_reports_reporter_target_member", columnNames = ["reporter_id", "target_member_id"]),
    ],
)
class Report private constructor(
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "reporter_id", nullable = false)
    val reporter: Member,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "report_type", nullable = false, length = 30)
    val reportType: ReportType,
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "target_product_id")
    val targetProduct: Product?,
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "target_member_id")
    val targetMember: Member?,
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 50)
    val reason: ReportReason,
    @field:Column(length = 500)
    val content: String?,
    /** 신고 증빙 이미지(선택). 신고 작성 시점에만 첨부 가능하며 이후에는 수정할 수 없다. */
    @field:Column(name = "evidence_image_url", length = 500)
    val evidenceImageUrl: String?,
) : BaseTimeEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 30)
    var status: ReportStatus = ReportStatus.RECEIVED
        protected set

    /** 관리자에 의한 신고 상태 변경 */
    fun changeStatus(status: ReportStatus) {
        this.status = status
    }

    /** 이 신고로 매너온도 등에 영향을 받는 대상 회원의 id. 상품 신고는 상품 소유자, 회원 신고는 대상 회원 본인이다. */
    fun resolveTargetMemberId(): Long = if (reportType == ReportType.MEMBER) targetMember!!.id!! else targetProduct!!.member.id!!

    companion object {
        @JvmStatic
        @JvmOverloads
        fun ofProduct(
            reporter: Member,
            targetProduct: Product,
            reason: ReportReason,
            content: String?,
            evidenceImageUrl: String? = null,
        ): Report = Report(reporter, ReportType.PRODUCT, targetProduct, null, reason, content, evidenceImageUrl)

        @JvmStatic
        @JvmOverloads
        fun ofMember(
            reporter: Member,
            targetMember: Member,
            reason: ReportReason,
            content: String?,
            evidenceImageUrl: String? = null,
        ): Report = Report(reporter, ReportType.MEMBER, null, targetMember, reason, content, evidenceImageUrl)
    }
}
