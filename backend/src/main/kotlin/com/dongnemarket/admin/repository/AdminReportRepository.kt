package com.dongnemarket.admin.repository

import com.dongnemarket.report.entity.Report
import com.dongnemarket.report.entity.ReportStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * Admin 전용 신고 Repository.
 * 팀원(report) Repository를 수정하지 않기 위해 admin 패키지에 별도로 둔다.
 * 신고 조회와 대시보드 집계에서 함께 사용한다.
 */
interface AdminReportRepository : JpaRepository<Report, Long> {
    fun countByStatus(status: ReportStatus): Long

    @Query("select r.evidenceImageUrl from Report r where r.evidenceImageUrl is not null")
    fun findAllEvidenceImageUrls(): List<String>
}
