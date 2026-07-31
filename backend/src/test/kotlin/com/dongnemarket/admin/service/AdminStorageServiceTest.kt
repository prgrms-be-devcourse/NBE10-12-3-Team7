package com.dongnemarket.admin.service

import com.dongnemarket.admin.dto.OrphanDeleteRequest
import com.dongnemarket.admin.dto.OrphanDeleteRequest.OrphanTarget
import com.dongnemarket.admin.repository.AdminProductImageRepository
import com.dongnemarket.admin.repository.AdminReportRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.storage.FileStorageService
import com.dongnemarket.global.storage.StorageException
import com.dongnemarket.global.storage.StoredObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Duration
import java.time.Instant

/**
 * [단위] AdminStorageService — 고아 대조(저장소목록 − DB참조) + grace 필터 + 삭제 재확인 로직만 검증.
 * FileStorageService·참조 레포는 목으로 격리한다. (실제 저장소/DB는 통합에서)
 */
@ExtendWith(MockitoExtension::class)
class AdminStorageServiceTest {
    @Mock
    lateinit var fileStorageService: FileStorageService

    @Mock
    lateinit var adminProductImageRepository: AdminProductImageRepository

    @Mock
    lateinit var adminReportRepository: AdminReportRepository

    @InjectMocks
    lateinit var adminStorageService: AdminStorageService

    private fun old(name: String): StoredObject = StoredObject(name, 100L, Instant.now().minus(Duration.ofDays(2)))

    private fun recent(name: String): StoredObject = StoredObject(name, 100L, Instant.now())

    /** report-evidence 쪽은 이 기능 대부분의 케이스에서 비어 있어, 공통으로 빈 스텁을 깐다. */
    private fun emptyReportSide() {
        given(fileStorageService.list(REPORT_EVIDENCE)).willReturn(emptyList())
        given(adminReportRepository.findAllEvidenceImageUrls()).willReturn(emptyList())
    }

    @Nested
    @DisplayName("scanOrphans")
    inner class Scan {
        @Test
        fun `DB가 참조하지 않는 오래된 파일만 고아로 잡고, 참조되는 파일은 제외한다`() {
            given(fileStorageService.list(PRODUCT_IMAGES)).willReturn(listOf(old("orphan.png"), old("used.png")))
            given(adminProductImageRepository.findAllImageUrls())
                .willReturn(listOf("/api/products/images/used.png"))
            emptyReportSide()

            val orphans = adminStorageService.scanOrphans(24)

            assertThat(orphans.map { it.filename }).containsExactly("orphan.png")
        }

        @Test
        fun `grace period 이내에 수정된 최근 파일은 제외한다`() {
            given(fileStorageService.list(PRODUCT_IMAGES)).willReturn(listOf(recent("fresh.png"), old("stale.png")))
            given(adminProductImageRepository.findAllImageUrls()).willReturn(emptyList())
            emptyReportSide()

            val orphans = adminStorageService.scanOrphans(24)

            assertThat(orphans.map { it.filename }).containsExactly("stale.png")
        }
    }

    @Nested
    @DisplayName("deleteOrphans")
    inner class Delete {
        @Test
        fun `지금도 고아인 대상은 삭제되고 deleted로 집계된다`() {
            given(fileStorageService.list(PRODUCT_IMAGES)).willReturn(listOf(old("orphan.png")))
            given(adminProductImageRepository.findAllImageUrls()).willReturn(emptyList())
            emptyReportSide()
            given(fileStorageService.delete("orphan.png", PRODUCT_IMAGES)).willReturn(true)

            val res =
                adminStorageService.deleteOrphans(
                    OrphanDeleteRequest(listOf(OrphanTarget(PRODUCT_IMAGES, "orphan.png"))),
                    24,
                )

            assertThat(res.requested).isEqualTo(1)
            assertThat(res.deleted).isEqualTo(1)
            assertThat(res.skipped).isZero()
        }

        @Test
        fun `지금은 고아가 아닌(참조되는) 대상은 삭제하지 않고 skip한다`() {
            given(fileStorageService.list(PRODUCT_IMAGES)).willReturn(listOf(old("used.png")))
            given(adminProductImageRepository.findAllImageUrls())
                .willReturn(listOf("/api/products/images/used.png"))
            emptyReportSide()

            val res =
                adminStorageService.deleteOrphans(
                    OrphanDeleteRequest(listOf(OrphanTarget(PRODUCT_IMAGES, "used.png"))),
                    24,
                )

            assertThat(res.requested).isEqualTo(1)
            assertThat(res.deleted).isZero()
            assertThat(res.skipped).isEqualTo(1)
            then(fileStorageService).should(never()).delete(anyString(), anyString())
        }

        @Test
        fun `허용되지 않은 디렉터리는 삭제 전에 INVALID_STORAGE_DIRECTORY로 거부한다`() {
            val ex =
                assertThrows<BusinessException> {
                    adminStorageService.deleteOrphans(
                        OrphanDeleteRequest(listOf(OrphanTarget("bad-dir", "x.png"))),
                        24,
                    )
                }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.INVALID_STORAGE_DIRECTORY)
        }

        @Test
        fun `저장소 삭제 중 오류는 STORAGE_ORPHAN_DELETE_FAILED로 변환한다`() {
            given(fileStorageService.list(PRODUCT_IMAGES)).willReturn(listOf(old("boom.png")))
            given(adminProductImageRepository.findAllImageUrls()).willReturn(emptyList())
            emptyReportSide()
            given(fileStorageService.delete("boom.png", PRODUCT_IMAGES))
                .willThrow(StorageException("fail", RuntimeException()))

            val ex =
                assertThrows<BusinessException> {
                    adminStorageService.deleteOrphans(
                        OrphanDeleteRequest(listOf(OrphanTarget(PRODUCT_IMAGES, "boom.png"))),
                        24,
                    )
                }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.STORAGE_ORPHAN_DELETE_FAILED)
        }
    }

    companion object {
        private const val PRODUCT_IMAGES = "product-images"
        private const val REPORT_EVIDENCE = "report-evidence"
    }
}
