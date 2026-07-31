package com.dongnemarket.admin.service

import com.dongnemarket.admin.dto.AdminDashboardResponse
import com.dongnemarket.admin.repository.AdminCommentRepository
import com.dongnemarket.admin.repository.AdminMemberRepository
import com.dongnemarket.admin.repository.AdminProductRepository
import com.dongnemarket.admin.repository.AdminReportRepository
import com.dongnemarket.report.entity.ReportStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AdminDashboardService(
    private val adminMemberRepository: AdminMemberRepository,
    private val adminProductRepository: AdminProductRepository,
    private val adminReportRepository: AdminReportRepository,
    private val adminCommentRepository: AdminCommentRepository,
) {
    /** 관리 대시보드 집계 (전체 회원·상품·신고·댓글 수 + 접수 대기 신고 수) */
    fun getDashboard(): AdminDashboardResponse =
        AdminDashboardResponse.of(
            adminMemberRepository.count(),
            adminProductRepository.count(),
            adminReportRepository.count(),
            adminReportRepository.countByStatus(ReportStatus.RECEIVED),
            adminCommentRepository.count(),
        )
}
