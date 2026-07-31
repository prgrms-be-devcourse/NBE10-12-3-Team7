package com.dongnemarket.admin.service

import com.dongnemarket.admin.dto.AdminReportResponse
import com.dongnemarket.admin.dto.AdminReportStatusUpdateRequest
import com.dongnemarket.admin.repository.AdminReportRepository
import com.dongnemarket.global.common.event.ReportStatusChangedEvent
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.manner.service.MannerScoreService
import com.dongnemarket.report.entity.Report
import com.dongnemarket.report.entity.ReportStatus
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
@Transactional(readOnly = true)
class AdminReportService(
    private val adminReportRepository: AdminReportRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val mannerScoreService: MannerScoreService,
) {
    /**
     * 전체 신고 목록. 미처리(RECEIVED/REVIEWING) 건을 먼저 보여주고, 그 안에서는
     * 신고 대상의 매너온도가 낮을수록(=이미 신뢰도가 떨어지는 회원일수록) 우선 노출되도록 가중 정렬한다.
     * 이미 처리된(COMPLETED/REJECTED) 건은 최신순으로 뒤에 배치한다.
     *
     * Java 의 Comparator.comparing().thenComparing() 체인이 compareBy/thenBy 로 대체된다.
     * Kotlin 쪽은 정렬 키가 null 이어도 NPE 대신 null 을 앞으로 보낸다(Java 는 NPE) —
     * 지금은 매너온도 맵이 같은 목록에서 만들어져 키가 항상 존재하므로 결과는 동일하다.
     */
    fun getReports(): List<AdminReportResponse> {
        val reports = adminReportRepository.findAll()
        val targetScoreByMemberId = loadTargetTrustScores(reports)

        return reports
            .sortedWith(
                compareBy<Report> { triageRank(it.status) }
                    .thenBy { targetScoreByMemberId[it.resolveTargetMemberId()] }
                    .thenByDescending { it.createdAt },
            ).map(AdminReportResponse::from)
    }

    private fun loadTargetTrustScores(reports: List<Report>): Map<Long, BigDecimal> {
        val targetMemberIds = reports.map(Report::resolveTargetMemberId).toSet()
        return mannerScoreService.getScoresByMemberIds(targetMemberIds)
    }

    /**
     * 미처리 건(RECEIVED/REVIEWING)을 항상 먼저, 처리 완료 건(COMPLETED/REJECTED)을 뒤로 보낸다.
     *
     * enum 을 다루는 when 은 모든 상수를 덮으면 else 가 필요 없다(컴파일러가 완전성을 검사한다).
     * 새 상태가 추가되면 여기서 컴파일 에러가 나므로 누락을 놓치지 않는다.
     */
    private fun triageRank(status: ReportStatus): Int =
        when (status) {
            ReportStatus.RECEIVED, ReportStatus.REVIEWING -> 0
            ReportStatus.COMPLETED, ReportStatus.REJECTED -> 1
        }

    /** 신고 단건 상세 */
    fun getReport(reportId: Long): AdminReportResponse {
        val report =
            adminReportRepository
                .findById(reportId)
                .orElseThrow { BusinessException(ErrorCode.REPORT_NOT_FOUND) }
        return AdminReportResponse.from(report)
    }

    /**
     * 신고 상태 변경 (RECEIVED/REVIEWING/COMPLETED/REJECTED).
     *
     * `request` 가 nullable 인 것은 원본 Java 시그니처를 그대로 옮긴 결과다 —
     * AdminReportServiceTest 가 request 자체에 null 을 넘겨 INVALID_REPORT_STATUS 방어를 검증한다.
     */
    @Transactional
    fun changeReportStatus(
        reportId: Long,
        request: AdminReportStatusUpdateRequest?,
    ): AdminReportResponse {
        val report =
            adminReportRepository
                .findById(reportId)
                .orElseThrow { BusinessException(ErrorCode.REPORT_NOT_FOUND) }
        val newStatus = parseStatus(request)
        if (report.status != newStatus) {
            report.changeStatus(newStatus)
            eventPublisher.publishEvent(ReportStatusChangedEvent(report.id, newStatus))
        }
        return AdminReportResponse.from(report)
    }

    private fun parseStatus(request: AdminReportStatusUpdateRequest?): ReportStatus {
        val status = request?.status
        if (status.isNullOrBlank()) {
            throw BusinessException(ErrorCode.INVALID_REPORT_STATUS)
        }
        return try {
            ReportStatus.valueOf(status.trim())
        } catch (e: IllegalArgumentException) {
            throw BusinessException(ErrorCode.INVALID_REPORT_STATUS)
        }
    }
}
