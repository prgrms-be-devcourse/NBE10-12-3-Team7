package com.dongnemarket.admin.ai.tool

import com.dongnemarket.admin.dto.AdminReportResponse
import com.dongnemarket.admin.service.AdminReportService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * 신고 조회 Tool (읽기 전용). 기존 AdminReportService에 위임만 한다.
 */
@Component
class AdminReportTools(
    private val adminReportService: AdminReportService,
) {
    @Tool(
        description =
            "전체 신고 목록을 조회한다. 상태(RECEIVED/REVIEWING/COMPLETED/REJECTED)와 무관하게 " +
                "모든 신고가 반환된다. 접수 대기(RECEIVED), 검토 중(REVIEWING) 등 특정 상태만 필요하면 " +
                "이 목록에서 status 값으로 걸러서 답하라.",
    )
    fun listReports(): List<AdminReportResponse> = adminReportService.getReports()

    @Tool(description = "신고 ID(숫자)로 신고 한 건의 상세 정보를 조회한다.")
    fun getReport(
        @ToolParam(description = "조회할 신고의 ID (양의 정수)") reportId: Long,
    ): AdminReportResponse = adminReportService.getReport(reportId)
}
