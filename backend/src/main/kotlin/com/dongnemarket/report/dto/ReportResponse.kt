package com.dongnemarket.report.dto

import com.dongnemarket.report.entity.Report
import com.dongnemarket.report.entity.ReportReason
import com.dongnemarket.report.entity.ReportStatus
import com.dongnemarket.report.entity.ReportType
import java.time.LocalDateTime

@ConsistentCopyVisibility
data class ReportResponse private constructor(
    val reportId: Long?,
    val reportType: ReportType,
    val reason: ReportReason,
    val status: ReportStatus,
    val evidenceImageUrl: String?,
    val createdAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        fun from(report: Report): ReportResponse =
            ReportResponse(
                report.id,
                report.reportType,
                report.reason,
                report.status,
                report.evidenceImageUrl,
                report.createdAt,
            )
    }
}
