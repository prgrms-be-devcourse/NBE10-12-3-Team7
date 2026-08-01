package com.dongnemarket.manner.listener

import com.dongnemarket.global.common.event.ProductCompletedEvent
import com.dongnemarket.global.common.event.ReportStatusChangedEvent
import com.dongnemarket.manner.service.MannerScoreService
import com.dongnemarket.member.entity.Member
import com.dongnemarket.product.entity.Product
import com.dongnemarket.report.entity.Report
import com.dongnemarket.report.entity.ReportReason
import com.dongnemarket.report.entity.ReportStatus
import com.dongnemarket.report.repository.ReportRepository
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.test.util.ReflectionTestUtils
import java.math.BigDecimal
import java.util.Optional

/**
 * [단위] MannerScoreEventListener — 신고 상태 변경/거래 완료 이벤트를 매너온도 반영으로 잇는 로직만 검증.
 *
 * MannerScoreService는 순수 Kotlin 클래스라 Mockito가 non-null 파라미터에 붙은 Kotlin의 @NotNull
 * 메타데이터를 감지해 `any()`/`any(Class)`를 "any(...) must not be null"로 방어적으로 거부한다
 * (mockito-kotlin이 없어도 되는 표준 우회가 [anyValid]다 — any() 호출은 매처 등록의 부수효과로만 쓰고,
 * 실제로 전달되는 값은 unchecked cast로 직접 만든다). `anyString()`/`anyLong()`처럼 프리미티브류
 * 전용 매처는 자체적으로 안전한 기본값(""/0)을 반환하므로 그대로 써도 된다.
 */
@ExtendWith(MockitoExtension::class)
class MannerScoreEventListenerTest {
    @Mock
    lateinit var mannerScoreService: MannerScoreService

    @Mock
    lateinit var reportRepository: ReportRepository

    @InjectMocks
    lateinit var listener: MannerScoreEventListener

    private fun reportWithId(
        id: Long,
        reporter: Member,
        target: Product,
        reason: ReportReason = ReportReason.FAKE_ITEM,
    ): Report {
        val report = Report.ofProduct(reporter, target, reason, "신고합니다")
        ReflectionTestUtils.setField(report, "id", id)
        return report
    }

    /** targetProduct의 소유자(피신고자)를 memberId로 설정한 mock 상품을 만든다. */
    private fun productWithSeller(memberId: Long): Product {
        val seller = mock(Member::class.java)
        given(seller.id).willReturn(memberId)
        val product = mock(Product::class.java)
        given(product.member).willReturn(seller)
        return product
    }

    @Nested
    @DisplayName("신고 상태 변경 - COMPLETED")
    inner class ReportCompleted {
        @Test
        fun `신고가 확정되면 대상의 매너온도가 사유 심각도만큼 하락한다`() {
            val target = productWithSeller(20L)
            val report = reportWithId(100L, mock(Member::class.java), target, ReportReason.FAKE_ITEM)
            given(reportRepository.findById(100L)).willReturn(Optional.of(report))
            given(mannerScoreService.severityOf("FAKE_ITEM")).willReturn(BigDecimal.valueOf(-1.0))
            given(mannerScoreService.countConfirmedReportsSince(eq(20L), anyValid())).willReturn(1L)

            listener.handleReportStatusChanged(ReportStatusChangedEvent(100L, ReportStatus.COMPLETED))

            verify(mannerScoreService).applyReportConfirmed(20L, BigDecimal.valueOf(-1.0), 100L)
        }

        @Test
        fun `최근 90일 확정 건수가 3건 이상이면 계정을 자동 정지시킨다`() {
            val target = productWithSeller(20L)
            val report = reportWithId(100L, mock(Member::class.java), target)
            given(reportRepository.findById(100L)).willReturn(Optional.of(report))
            given(mannerScoreService.severityOf(anyString())).willReturn(BigDecimal.valueOf(-1.0))
            given(mannerScoreService.countConfirmedReportsSince(eq(20L), anyValid())).willReturn(3L)

            listener.handleReportStatusChanged(ReportStatusChangedEvent(100L, ReportStatus.COMPLETED))

            verify(mannerScoreService).suspend(20L)
        }

        @Test
        fun `최근 90일 확정 건수가 3건 미만이면 정지시키지 않는다`() {
            val target = productWithSeller(20L)
            val report = reportWithId(100L, mock(Member::class.java), target)
            given(reportRepository.findById(100L)).willReturn(Optional.of(report))
            given(mannerScoreService.severityOf(anyString())).willReturn(BigDecimal.valueOf(-1.0))
            given(mannerScoreService.countConfirmedReportsSince(eq(20L), anyValid())).willReturn(2L)

            listener.handleReportStatusChanged(ReportStatusChangedEvent(100L, ReportStatus.COMPLETED))

            verify(mannerScoreService, never()).suspend(anyLong())
        }
    }

    @Nested
    @DisplayName("신고 상태 변경 - REJECTED / 기타")
    inner class ReportRejectedOrOther {
        @Test
        fun `신고가 무고성으로 판정되면 신고자의 매너온도가 하락한다`() {
            val reporter = mock(Member::class.java)
            given(reporter.id).willReturn(1L)
            val report = reportWithId(100L, reporter, mock(Product::class.java))
            given(reportRepository.findById(100L)).willReturn(Optional.of(report))

            listener.handleReportStatusChanged(ReportStatusChangedEvent(100L, ReportStatus.REJECTED))

            verify(mannerScoreService).applyFalseReportPenalty(1L, 100L)
        }

        @Test
        fun `신고가 그 사이 취소(삭제)됐으면 아무 것도 반영하지 않는다`() {
            given(reportRepository.findById(999L)).willReturn(Optional.empty())

            listener.handleReportStatusChanged(ReportStatusChangedEvent(999L, ReportStatus.COMPLETED))

            verify(mannerScoreService, never()).applyReportConfirmed(anyLong(), anyValid(), anyLong())
        }

        @Test
        fun `RECEIVED REVIEWING 등 완료 무고 외 상태는 아무 것도 반영하지 않는다`() {
            val report = reportWithId(100L, mock(Member::class.java), mock(Product::class.java))
            given(reportRepository.findById(100L)).willReturn(Optional.of(report))

            listener.handleReportStatusChanged(ReportStatusChangedEvent(100L, ReportStatus.REVIEWING))

            verify(mannerScoreService, never()).applyReportConfirmed(anyLong(), anyValid(), anyLong())
            verify(mannerScoreService, never()).applyFalseReportPenalty(anyLong(), anyLong())
        }
    }

    @Test
    fun `상품 거래완료 이벤트를 받으면 판매자의 매너온도에 거래완료 보너스가 반영된다`() {
        listener.handleProductCompleted(ProductCompletedEvent(productId = 10L, sellerId = 5L))

        verify(mannerScoreService).applyTradeCompleted(5L)
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> anyValid(): T {
    ArgumentMatchers.any<T>()
    return null as T
}
