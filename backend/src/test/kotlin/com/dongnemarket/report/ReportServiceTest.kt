package com.dongnemarket.report

import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.report.dto.MemberReportCreateRequest
import com.dongnemarket.report.dto.ProductReportCreateRequest
import com.dongnemarket.report.entity.Report
import com.dongnemarket.report.entity.ReportReason
import com.dongnemarket.report.entity.ReportStatus
import com.dongnemarket.report.repository.ReportRepository
import com.dongnemarket.report.service.EvidenceImageStorageService
import com.dongnemarket.report.service.ReportService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.util.ReflectionTestUtils
import java.math.BigDecimal
import java.util.Optional

/** [단위] ReportService — 상품/사용자 신고 생성, 중복·본인신고 방어, 내 신고 조회/취소. */
@ExtendWith(MockitoExtension::class)
class ReportServiceTest {
    @Mock
    lateinit var reportRepository: ReportRepository

    @Mock
    lateinit var memberRepository: MemberRepository

    @Mock
    lateinit var productRepository: ProductRepository

    @Mock
    lateinit var evidenceImageStorageService: EvidenceImageStorageService

    @InjectMocks
    lateinit var reportService: ReportService

    private fun member(
        id: Long,
        email: String,
        nickname: String,
    ): Member {
        val member = Member.createUser(email, "pw", nickname)
        ReflectionTestUtils.setField(member, "id", id)
        return member
    }

    private fun product(ownerId: Long): Product {
        val owner = member(ownerId, "owner-$ownerId@example.com", "owner$ownerId")
        return Product.create(owner, null, "테스트 상품", "설명", BigDecimal.valueOf(10000), yeoksam())
    }

    private fun yeoksam(): Region {
        val seoul = Region.root("1100000000", "서울특별시", "서울특별시")
        val gangnam = Region.child("1168000000", 2, seoul, "서울특별시 강남구", "강남구")
        return Region.child("1168010100", 3, gangnam, "서울특별시 강남구 역삼동", "역삼동")
    }

    private fun productRequest(evidenceImageUrl: String? = null) =
        ProductReportCreateRequest(ReportReason.FAKE_ITEM, "가품 같아요", evidenceImageUrl)

    private fun memberRequest() = MemberReportCreateRequest(ReportReason.FRAUD_SUSPECTED, "사기 의심", null)

    @Nested
    @DisplayName("상품 신고 — 성공 케이스")
    inner class ReportProductSuccess {
        @Test
        fun `로그인 사용자가 상품을 신고하면 RECEIVED 상태로 신고가 저장된다`() {
            val reporterId = 1L
            val targetProductId = 10L
            val reporter = member(reporterId, "reporter@example.com", "신고자")
            val product = product(2L)
            given(memberRepository.findById(reporterId)).willReturn(Optional.of(reporter))
            given(productRepository.findById(targetProductId)).willReturn(Optional.of(product))
            given(reportRepository.existsByReporterAndTargetProduct(reporter, product)).willReturn(false)
            given(reportRepository.save(any(Report::class.java))).willAnswer { it.arguments[0] }

            val response = reportService.reportProduct(reporterId, targetProductId, productRequest())

            assertThat(response.reportType.name).isEqualTo("PRODUCT")
            assertThat(response.reason).isEqualTo(ReportReason.FAKE_ITEM)
            assertThat(response.status).isEqualTo(ReportStatus.RECEIVED)
        }

        @Test
        fun `증빙 이미지를 첨부해 상품을 신고하면 응답에 이미지 URL이 포함된다`() {
            val reporterId = 1L
            val targetProductId = 10L
            val reporter = member(reporterId, "reporter@example.com", "신고자")
            val product = product(2L)
            given(memberRepository.findById(reporterId)).willReturn(Optional.of(reporter))
            given(productRepository.findById(targetProductId)).willReturn(Optional.of(product))
            given(reportRepository.existsByReporterAndTargetProduct(reporter, product)).willReturn(false)
            given(reportRepository.save(any(Report::class.java))).willAnswer { it.arguments[0] }

            val response =
                reportService.reportProduct(
                    reporterId,
                    targetProductId,
                    productRequest("https://example.com/evidence.jpg"),
                )

            assertThat(response.evidenceImageUrl).isEqualTo("https://example.com/evidence.jpg")
        }

        @Test
        fun `증빙 이미지 없이 신고하면 응답의 이미지 URL은 null이다`() {
            val reporterId = 1L
            val targetProductId = 10L
            val reporter = member(reporterId, "reporter@example.com", "신고자")
            val product = product(2L)
            given(memberRepository.findById(reporterId)).willReturn(Optional.of(reporter))
            given(productRepository.findById(targetProductId)).willReturn(Optional.of(product))
            given(reportRepository.existsByReporterAndTargetProduct(reporter, product)).willReturn(false)
            given(reportRepository.save(any(Report::class.java))).willAnswer { it.arguments[0] }

            val response = reportService.reportProduct(reporterId, targetProductId, productRequest())

            assertThat(response.evidenceImageUrl).isNull()
        }
    }

    @Nested
    @DisplayName("상품 신고 — 실패 케이스")
    inner class ReportProductFailure {
        @Test
        fun `본인이 등록한 상품을 신고하면 CANNOT_REPORT_OWN_PRODUCT 예외가 발생한다`() {
            val reporterId = 1L
            val targetProductId = 10L
            val reporter = member(reporterId, "reporter@example.com", "신고자")
            val product = product(reporterId)
            given(memberRepository.findById(reporterId)).willReturn(Optional.of(reporter))
            given(productRepository.findById(targetProductId)).willReturn(Optional.of(product))

            val ex =
                assertThrows<BusinessException> {
                    reportService.reportProduct(reporterId, targetProductId, productRequest())
                }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.CANNOT_REPORT_OWN_PRODUCT)
        }

        @Test
        fun `같은 상품을 중복 신고하면 DUPLICATE_REPORT 예외가 발생한다`() {
            val reporterId = 1L
            val targetProductId = 10L
            val reporter = member(reporterId, "reporter@example.com", "신고자")
            val product = product(2L)
            given(memberRepository.findById(reporterId)).willReturn(Optional.of(reporter))
            given(productRepository.findById(targetProductId)).willReturn(Optional.of(product))
            given(reportRepository.existsByReporterAndTargetProduct(reporter, product)).willReturn(true)

            val ex =
                assertThrows<BusinessException> {
                    reportService.reportProduct(reporterId, targetProductId, productRequest())
                }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.DUPLICATE_REPORT)
        }

        @Test
        fun `existsBy 통과 후 저장 시점에 DB 유니크 제약을 위반해도 DUPLICATE_REPORT로 변환된다 (동시 요청 대비)`() {
            val reporterId = 1L
            val targetProductId = 10L
            val reporter = member(reporterId, "reporter@example.com", "신고자")
            val product = product(2L)
            given(memberRepository.findById(reporterId)).willReturn(Optional.of(reporter))
            given(productRepository.findById(targetProductId)).willReturn(Optional.of(product))
            given(reportRepository.existsByReporterAndTargetProduct(reporter, product)).willReturn(false)
            given(reportRepository.save(any(Report::class.java)))
                .willThrow(DataIntegrityViolationException("duplicate key"))

            val ex =
                assertThrows<BusinessException> {
                    reportService.reportProduct(reporterId, targetProductId, productRequest())
                }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.DUPLICATE_REPORT)
        }
    }

    @Nested
    @DisplayName("사용자 신고")
    inner class ReportMember {
        @Test
        fun `로그인 사용자가 다른 회원을 신고하면 RECEIVED 상태로 신고가 저장된다`() {
            val reporterId = 1L
            val targetMemberId = 2L
            val reporter = member(reporterId, "reporter@example.com", "신고자")
            val targetMember = member(targetMemberId, "target@example.com", "신고대상")
            given(memberRepository.findById(reporterId)).willReturn(Optional.of(reporter))
            given(memberRepository.findById(targetMemberId)).willReturn(Optional.of(targetMember))
            given(reportRepository.existsByReporterAndTargetMember(reporter, targetMember)).willReturn(false)
            given(reportRepository.save(any(Report::class.java))).willAnswer { it.arguments[0] }

            val response = reportService.reportMember(reporterId, targetMemberId, memberRequest())

            assertThat(response.reportType.name).isEqualTo("MEMBER")
            assertThat(response.reason).isEqualTo(ReportReason.FRAUD_SUSPECTED)
        }

        @Test
        fun `본인 계정을 신고하면 CANNOT_REPORT_SELF 예외가 발생한다`() {
            val reporterId = 1L
            val reporter = member(reporterId, "reporter@example.com", "신고자")
            given(memberRepository.findById(reporterId)).willReturn(Optional.of(reporter))

            val ex =
                assertThrows<BusinessException> {
                    reportService.reportMember(reporterId, reporterId, memberRequest())
                }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.CANNOT_REPORT_SELF)
        }

        @Test
        fun `이미 신고한 회원을 다시 신고하면 DUPLICATE_REPORT 예외가 발생한다`() {
            val reporterId = 1L
            val targetMemberId = 2L
            val reporter = member(reporterId, "reporter@example.com", "신고자")
            val targetMember = member(targetMemberId, "target@example.com", "신고대상")
            given(memberRepository.findById(reporterId)).willReturn(Optional.of(reporter))
            given(memberRepository.findById(targetMemberId)).willReturn(Optional.of(targetMember))
            given(reportRepository.existsByReporterAndTargetMember(reporter, targetMember)).willReturn(true)

            val ex =
                assertThrows<BusinessException> {
                    reportService.reportMember(reporterId, targetMemberId, memberRequest())
                }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.DUPLICATE_REPORT)
        }
    }

    @Nested
    @DisplayName("신고 취소")
    inner class CancelReport {
        @Test
        fun `RECEIVED 상태인 본인 신고를 취소하면 삭제된다`() {
            val reporterId = 1L
            val reportId = 100L
            val reporter = member(reporterId, "reporter@example.com", "신고자")
            val report = Report.ofProduct(reporter, product(2L), ReportReason.FAKE_ITEM, "신고합니다")
            given(reportRepository.findById(reportId)).willReturn(Optional.of(report))

            reportService.cancelReport(reporterId, reportId)

            verify(reportRepository).delete(report)
        }

        @Test
        fun `존재하지 않는 신고를 취소하면 REPORT_NOT_FOUND 예외가 발생한다`() {
            val reporterId = 1L
            val reportId = 999L
            given(reportRepository.findById(reportId)).willReturn(Optional.empty())

            val ex = assertThrows<BusinessException> { reportService.cancelReport(reporterId, reportId) }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.REPORT_NOT_FOUND)
        }

        @Test
        fun `타인의 신고를 취소하려 하면 REPORT_OWNER_ONLY 예외가 발생한다`() {
            val reporterId = 1L
            val otherReporterId = 2L
            val reportId = 100L
            val otherReporter = member(otherReporterId, "other@example.com", "다른신고자")
            val report = Report.ofProduct(otherReporter, product(3L), ReportReason.FAKE_ITEM, "신고합니다")
            given(reportRepository.findById(reportId)).willReturn(Optional.of(report))

            val ex = assertThrows<BusinessException> { reportService.cancelReport(reporterId, reportId) }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.REPORT_OWNER_ONLY)
        }

        @Test
        fun `이미 처리 중인 신고를 취소하려 하면 CANNOT_CANCEL_REPORT 예외가 발생한다`() {
            val reporterId = 1L
            val reportId = 100L
            val reporter = member(reporterId, "reporter@example.com", "신고자")
            val report = Report.ofProduct(reporter, product(2L), ReportReason.FAKE_ITEM, "신고합니다")
            report.changeStatus(ReportStatus.REVIEWING)
            given(reportRepository.findById(reportId)).willReturn(Optional.of(report))

            val ex = assertThrows<BusinessException> { reportService.cancelReport(reporterId, reportId) }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.CANNOT_CANCEL_REPORT)
        }
    }
}
