package com.dongnemarket.report.dto

import com.dongnemarket.report.entity.Report
import com.dongnemarket.report.entity.ReportReason
import com.dongnemarket.report.entity.ReportStatus
import com.dongnemarket.report.entity.ReportType
import java.time.LocalDateTime

@ConsistentCopyVisibility
data class MyReportResponse private constructor(
    val reportId: Long?,
    val reportType: ReportType,
    val targetId: Long?,
    val reason: ReportReason,
    val content: String?,
    val status: ReportStatus,
    val evidenceImageUrl: String?,
    val createdAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        fun from(report: Report): MyReportResponse =
            MyReportResponse(
                report.id,
                report.reportType,
                report.targetId,
                report.reason,
                report.content,
                report.status,
                report.evidenceImageUrl,
                report.createdAt,
            )
    }
}

/** 신고 대상 id — 상품 신고는 대상 상품 id, 회원 신고는 대상 회원 id. Report.resolveTargetMemberId()(매너온도 반영 대상)와는 다른 값이다. */
private val Report.targetId: Long?
    get() = if (reportType == ReportType.PRODUCT) targetProduct!!.id else targetMember!!.id
