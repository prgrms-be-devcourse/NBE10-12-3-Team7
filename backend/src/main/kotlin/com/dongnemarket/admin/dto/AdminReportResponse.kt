package com.dongnemarket.admin.dto

import com.dongnemarket.report.entity.Report
import com.dongnemarket.report.entity.ReportReason
import com.dongnemarket.report.entity.ReportStatus
import com.dongnemarket.report.entity.ReportType
import java.time.LocalDateTime

/**
 * 관리자용 신고 응답. 신고자·대상·사유·상태 등 관리에 필요한 정보를 모두 노출한다.
 *
 * 원본 Java 는 private 생성자가 Report 를 직접 받아 안에서 매핑했다. data class 는 프로퍼티가
 * 주 생성자에 있어야 equals/copy 를 생성할 수 있으므로, 값을 받는 형태로 바꾸고 매핑은 [from] 으로 옮겼다
 * (형태가 같은 패키지의 다른 응답 DTO 와 통일된다).
 */
@ConsistentCopyVisibility
data class AdminReportResponse private constructor(
    val reportId: Long?,
    val reporterId: Long?,
    val targetMemberId: Long?,
    val targetProductId: Long?,
    val reportType: ReportType,
    val reason: ReportReason,
    val content: String?,
    val status: ReportStatus,
    val createdAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        fun from(report: Report): AdminReportResponse =
            AdminReportResponse(
                report.id,
                report.reporter.id,
                // Java 의 `getTargetMember() != null ? getTargetMember().getId() : null` 을
                // 안전 호출 한 번으로 대체한다(getter 중복 호출도 사라진다).
                report.targetMember?.id,
                report.targetProduct?.id,
                report.reportType,
                report.reason,
                // Report.content 는 @Column(length = 500) 으로 nullable = false 가 없다 → null 허용.
                report.content,
                report.status,
                report.createdAt,
            )
    }
}
