package com.dongnemarket.manner.listener;

import com.dongnemarket.global.common.event.ProductCompletedEvent;
import com.dongnemarket.global.common.event.ReportStatusChangedEvent;
import com.dongnemarket.manner.service.MannerScoreService;
import com.dongnemarket.report.entity.Report;
import com.dongnemarket.report.entity.ReportStatus;
import com.dongnemarket.report.repository.ReportRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 신고 상태 변경·거래 완료 이벤트를 구독해 매너온도에 반영하는 핸들러.
 * <p>{@code AFTER_COMMIT} + {@code REQUIRES_NEW}로 동작한다(notification 도메인과 동일한 패턴) —
 * 원본 작업(신고 상태 변경, 상품 거래완료 처리)이 실제로 커밋된 뒤에만 반영하고,
 * 매너온도 반영이 실패해도 원본 작업을 롤백시키지 않는다(best-effort).
 * <p>신고 확정 시 최근 90일 내 확정 건수가 3건 이상이면 계정을 자동 정지시킨다.
 */
@Component
public class MannerScoreEventListener {

    private static final long SUSPEND_THRESHOLD_COUNT = 3;
    private static final long SUSPEND_WINDOW_DAYS = 90;

    private final MannerScoreService mannerScoreService;
    private final ReportRepository reportRepository;

    public MannerScoreEventListener(MannerScoreService mannerScoreService, ReportRepository reportRepository) {
        this.mannerScoreService = mannerScoreService;
        this.reportRepository = reportRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleReportStatusChanged(ReportStatusChangedEvent event) {
        Report report = reportRepository.findById(event.reportId()).orElse(null);
        if (report == null) {
            return; // 신고가 그 사이 삭제(취소)됐으면 반영할 것이 없다
        }

        if (event.newStatus() == ReportStatus.COMPLETED) {
            Long targetMemberId = report.resolveTargetMemberId();
            BigDecimal severity = mannerScoreService.severityOf(report.getReason().name());
            mannerScoreService.applyReportConfirmed(targetMemberId, severity, report.getId());

            long recentConfirmedCount = mannerScoreService.countConfirmedReportsSince(
                    targetMemberId, LocalDateTime.now().minusDays(SUSPEND_WINDOW_DAYS));
            if (recentConfirmedCount >= SUSPEND_THRESHOLD_COUNT) {
                mannerScoreService.suspend(targetMemberId);
            }
        } else if (event.newStatus() == ReportStatus.REJECTED) {
            mannerScoreService.applyFalseReportPenalty(report.getReporter().getId(), report.getId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleProductCompleted(ProductCompletedEvent event) {
        mannerScoreService.applyTradeCompleted(event.getSellerId());
    }
}
