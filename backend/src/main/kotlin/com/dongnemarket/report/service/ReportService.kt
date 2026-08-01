package com.dongnemarket.report.service

import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.report.dto.EvidenceImageUploadResponse
import com.dongnemarket.report.dto.MemberReportCreateRequest
import com.dongnemarket.report.dto.MyReportResponse
import com.dongnemarket.report.dto.ProductReportCreateRequest
import com.dongnemarket.report.dto.ReportResponse
import com.dongnemarket.report.entity.Report
import com.dongnemarket.report.entity.ReportStatus
import com.dongnemarket.report.repository.ReportRepository
import org.springframework.core.io.Resource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

@Service
class ReportService(
    private val reportRepository: ReportRepository,
    private val memberRepository: MemberRepository,
    private val productRepository: ProductRepository,
    private val evidenceImageStorageService: EvidenceImageStorageService,
) {
    /** 신고 증빙 이미지를 저장하고, 신고 생성 요청에 그대로 넣을 수 있는 접근 URL을 반환한다. */
    fun uploadEvidenceImage(file: MultipartFile): EvidenceImageUploadResponse {
        val filename = evidenceImageStorageService.store(file)
        return EvidenceImageUploadResponse.of("/api/reports/evidence-image/$filename")
    }

    fun loadEvidenceImage(filename: String): Resource = evidenceImageStorageService.load(filename)

    @Transactional
    fun reportProduct(
        reporterId: Long,
        targetProductId: Long,
        request: ProductReportCreateRequest,
    ): ReportResponse {
        val reporter = memberRepository.findById(reporterId).orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
        val product = productRepository.findById(targetProductId).orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }

        if (product.member.id == reporterId) {
            throw BusinessException(ErrorCode.CANNOT_REPORT_OWN_PRODUCT)
        }

        if (reportRepository.existsByReporterAndTargetProduct(reporter, product)) {
            throw BusinessException(ErrorCode.DUPLICATE_REPORT)
        }

        // reason은 @Valid가 이미 non-null을 보장한 뒤에만 이 메서드에 도달한다(컨트롤러의 @Valid).
        val report = Report.ofProduct(reporter, product, request.reason!!, request.content, request.evidenceImageUrl)
        return ReportResponse.from(saveReport(report))
    }

    @Transactional
    fun reportMember(
        reporterId: Long,
        targetMemberId: Long,
        request: MemberReportCreateRequest,
    ): ReportResponse {
        val reporter = memberRepository.findById(reporterId).orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }

        if (reporterId == targetMemberId) {
            throw BusinessException(ErrorCode.CANNOT_REPORT_SELF)
        }

        val targetMember = memberRepository.findById(targetMemberId).orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }

        if (reportRepository.existsByReporterAndTargetMember(reporter, targetMember)) {
            throw BusinessException(ErrorCode.DUPLICATE_REPORT)
        }

        val report = Report.ofMember(reporter, targetMember, request.reason!!, request.content, request.evidenceImageUrl)
        return ReportResponse.from(saveReport(report))
    }

    /**
     * 애플리케이션 레벨의 existsBy... 중복 검증은 동시 요청(레이스 컨디션)에서는 뚫릴 수 있다.
     * 이 경우 DB 유니크 제약(uk_reports_reporter_target_product / uk_reports_reporter_target_member)이
     * 최종 방어선 역할을 하며, 그 위반을 동일한 DUPLICATE_REPORT 비즈니스 예외로 변환해 API 응답을 일관되게 만든다.
     */
    private fun saveReport(report: Report): Report =
        try {
            reportRepository.save(report)
        } catch (e: DataIntegrityViolationException) {
            throw BusinessException(ErrorCode.DUPLICATE_REPORT)
        }

    @Transactional(readOnly = true)
    fun getMyReports(reporterId: Long): List<MyReportResponse> {
        val reporter = memberRepository.findById(reporterId).orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
        return reportRepository.findAllByReporter(reporter).map { MyReportResponse.from(it) }
    }

    /** 본인이 접수한 신고 단건 상세 조회 — 신고 사유 상세 내용(content)까지 포함해서 반환한다. */
    @Transactional(readOnly = true)
    fun getMyReport(
        reporterId: Long,
        reportId: Long,
    ): MyReportResponse {
        val report = reportRepository.findById(reportId).orElseThrow { BusinessException(ErrorCode.REPORT_NOT_FOUND) }

        if (report.reporter.id != reporterId) {
            throw BusinessException(ErrorCode.REPORT_OWNER_ONLY)
        }

        return MyReportResponse.from(report)
    }

    /** 아직 처리되지 않은(RECEIVED) 본인 신고만 취소(삭제)할 수 있다. */
    @Transactional
    fun cancelReport(
        reporterId: Long,
        reportId: Long,
    ) {
        val report = reportRepository.findById(reportId).orElseThrow { BusinessException(ErrorCode.REPORT_NOT_FOUND) }

        if (report.reporter.id != reporterId) {
            throw BusinessException(ErrorCode.REPORT_OWNER_ONLY)
        }
        if (report.status != ReportStatus.RECEIVED) {
            throw BusinessException(ErrorCode.CANNOT_CANCEL_REPORT)
        }

        reportRepository.delete(report)
    }
}
