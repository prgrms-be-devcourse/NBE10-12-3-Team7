package com.dongnemarket.admin.ai.tool

import com.dongnemarket.admin.dto.AdminDashboardResponse
import com.dongnemarket.admin.service.AdminDashboardService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

/**
 * 대시보드 집계 Tool (읽기 전용). 기존 AdminDashboardService에 위임만 한다.
 */
@Component
class AdminDashboardTools(
    private val adminDashboardService: AdminDashboardService,
) {
    @Tool(
        description =
            "관리 대시보드 집계를 조회한다. 전체 회원 수, 전체 상품 수, 전체 신고 수, " +
                "접수 대기(RECEIVED) 신고 수, 전체 댓글 수를 반환한다. " +
                "'현황', '통계', '몇 명', '몇 건' 같은 전체 규모 질문에는 목록 조회 대신 이 tool을 먼저 사용하라.",
    )
    fun getDashboard(): AdminDashboardResponse = adminDashboardService.getDashboard()
}
