package com.dongnemarket.admin.dto

/**
 * 관리 대시보드 집계 응답. 회원·상품·신고·댓글 현황을 한눈에 보여준다.
 */
@ConsistentCopyVisibility
data class AdminDashboardResponse private constructor(
    val totalMembers: Long,
    val totalProducts: Long,
    val totalReports: Long,
    val pendingReports: Long,
    val totalComments: Long,
) {
    companion object {
        @JvmStatic
        fun of(
            totalMembers: Long,
            totalProducts: Long,
            totalReports: Long,
            pendingReports: Long,
            totalComments: Long,
        ): AdminDashboardResponse = AdminDashboardResponse(totalMembers, totalProducts, totalReports, pendingReports, totalComments)
    }
}
