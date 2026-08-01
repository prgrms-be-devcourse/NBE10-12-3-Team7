package com.dongnemarket.admin.service

import com.dongnemarket.admin.dto.AdminReportStatusUpdateRequest
import com.dongnemarket.admin.repository.AdminReportRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.manner.service.MannerScoreService
import com.dongnemarket.member.entity.Member
import com.dongnemarket.product.entity.Product
import com.dongnemarket.report.entity.Report
import com.dongnemarket.report.entity.ReportReason
import com.dongnemarket.report.entity.ReportStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.context.ApplicationEventPublisher
import java.util.Optional

/**
 * [단위] AdminReportService.changeReportStatus — 서비스 고유 로직만 검증(Member와 동일 구조).
 *  - 검증 대상: parseStatus(파싱·검증) + REPORT_NOT_FOUND 예외.
 *  - 제외: changeStatus 단순 세팅(엔티티), getReports/getReport 위임(통합), 상태 역행 허용(AD-47 갭 → 통합).
 */
@ExtendWith(MockitoExtension::class)
class AdminReportServiceTest {
    @Mock
    lateinit var adminReportRepository: AdminReportRepository

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    @Mock
    lateinit var mannerScoreService: MannerScoreService

    @InjectMocks
    lateinit var adminReportService: AdminReportService

    private fun existingReport(): Report =
        Report.ofProduct(mock(Member::class.java), mock(Product::class.java), ReportReason.FRAUD_SUSPECTED, "사기 의심 신고")

    @Nested
    @DisplayName("성공 케이스")
    inner class Success {
        @Test
        fun `앞뒤 공백이 있어도 trim 후 파싱되어 상태가 변경된다`() {
            val report = existingReport()
            given(adminReportRepository.findById(1L)).willReturn(Optional.of(report))

            val response = adminReportService.changeReportStatus(1L, AdminReportStatusUpdateRequest("  REVIEWING  "))

            assertThat(response.status).isEqualTo(ReportStatus.REVIEWING)
            assertThat(report.status).isEqualTo(ReportStatus.REVIEWING)
        }
    }

    @Nested
    @DisplayName("실패 케이스")
    inner class Failure {
        /**
         * null·공백·정의되지 않은 값·대소문자 불일치는 모두 같은 분기(INVALID_REPORT_STATUS)로 귀결.
         * changeReportStatus가 findById를 먼저 호출하므로 신고 존재를 스텁해야 NOT_FOUND가 먼저 터지지 않는다.
         */
        @ParameterizedTest(name = "[{index}] status=\"{0}\" → INVALID_REPORT_STATUS")
        @NullSource
        @ValueSource(strings = ["", "   ", "FOO", "reviewing", "Completed"])
        @DisplayName("상태값이 null·공백·오타·대소문자 불일치면 INVALID_REPORT_STATUS")
        fun invalidStatusValue_throwsInvalid(status: String?) {
            given(adminReportRepository.findById(1L)).willReturn(Optional.of(existingReport()))

            val ex =
                assertThrows<BusinessException> {
                    adminReportService.changeReportStatus(1L, AdminReportStatusUpdateRequest(status))
                }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.INVALID_REPORT_STATUS)
        }

        @Test
        fun `요청 객체 자체가 null이어도 INVALID_REPORT_STATUS로 방어한다`() {
            given(adminReportRepository.findById(1L)).willReturn(Optional.of(existingReport()))

            val ex = assertThrows<BusinessException> { adminReportService.changeReportStatus(1L, null) }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.INVALID_REPORT_STATUS)
        }

        @Test
        fun `존재하지 않는 신고의 상태를 변경하면 REPORT_NOT_FOUND 예외가 발생한다`() {
            given(adminReportRepository.findById(999L)).willReturn(Optional.empty())

            val ex =
                assertThrows<BusinessException> {
                    adminReportService.changeReportStatus(999L, AdminReportStatusUpdateRequest("REVIEWING"))
                }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.REPORT_NOT_FOUND)
        }
    }
}
