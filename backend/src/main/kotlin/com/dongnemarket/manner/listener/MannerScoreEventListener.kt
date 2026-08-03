package com.dongnemarket.manner.listener

import com.dongnemarket.global.common.event.ProductCompletedEvent
import com.dongnemarket.global.common.event.ReportStatusChangedEvent
import com.dongnemarket.manner.service.MannerScoreService
import com.dongnemarket.report.entity.ReportStatus
import com.dongnemarket.report.repository.ReportRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.LocalDateTime

/**
 * 신고 상태 변경·거래 완료 이벤트를 구독해 매너온도에 반영하는 핸들러.
 *
 * `AFTER_COMMIT` + `REQUIRES_NEW`로 동작한다(notification 도메인과 동일한 패턴) —
 * 원본 작업(신고 상태 변경, 상품 거래완료 처리)이 실제로 커밋된 뒤에만 반영하고,
 * 매너온도 반영이 실패해도 원본 작업을 롤백시키지 않는다(best-effort).
 *
 * 신고 확정 시 최근 90일 내 확정 건수가 3건 이상이면 계정을 자동 정지시킨다.
 */
@Component
class MannerScoreEventListener(
    private val mannerScoreService: MannerScoreService,
    private val reportRepository: ReportRepository,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun handleReportStatusChanged(event: ReportStatusChangedEvent) {
        // 신고가 그 사이 삭제(취소)됐으면 반영할 것이 없다.
        // reportId 는 이벤트 계약상 nullable 이지만(영속 전 Report 로도 발행될 수 있다),
        // 이 리스너는 AFTER_COMMIT 에 돌아 항상 영속된 신고를 받는다. findById 가 non-null 을 요구해 여기서 확인한다.
        val report = reportRepository.findById(checkNotNull(event.reportId)).orElse(null) ?: return

        when (event.newStatus) {
            ReportStatus.COMPLETED -> {
                val targetMemberId = report.resolveTargetMemberId()
                val severity = mannerScoreService.severityOf(report.reason.name)
                mannerScoreService.applyReportConfirmed(targetMemberId, severity, report.id!!)

                val recentConfirmedCount =
                    mannerScoreService.countConfirmedReportsSince(
                        targetMemberId,
                        LocalDateTime.now().minusDays(SUSPEND_WINDOW_DAYS),
                    )
                if (recentConfirmedCount >= SUSPEND_THRESHOLD_COUNT) {
                    mannerScoreService.suspend(targetMemberId)
                }
            }
            ReportStatus.REJECTED -> {
                mannerScoreService.applyFalseReportPenalty(report.reporter.id!!, report.id!!)
            }
            else -> {}
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun handleProductCompleted(event: ProductCompletedEvent) {
        mannerScoreService.applyTradeCompleted(event.sellerId)
    }

    companion object {
        private const val SUSPEND_THRESHOLD_COUNT = 3L
        private const val SUSPEND_WINDOW_DAYS = 90L
    }
}
