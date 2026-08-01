package com.dongnemarket.admin.service

import com.dongnemarket.admin.dto.OrphanDeleteRequest
import com.dongnemarket.admin.dto.OrphanDeleteResponse
import com.dongnemarket.admin.dto.OrphanFileResponse
import com.dongnemarket.admin.repository.AdminProductImageRepository
import com.dongnemarket.admin.repository.AdminReportRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.storage.FileStorageService
import com.dongnemarket.global.storage.StorageException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * 저장소 고아파일(파일은 실재하나 DB가 참조하지 않는 것) 스캔·삭제.
 * directory별로 FileStorageService.list()와 DB 참조 집합을 대조한다. 조회는 읽기 전용.
 */
@Service
@Transactional(readOnly = true)
class AdminStorageService(
    private val fileStorageService: FileStorageService,
    private val adminProductImageRepository: AdminProductImageRepository,
    private val adminReportRepository: AdminReportRepository,
) {
    /**
     * graceHours 이내 수정 파일(업로드 진행 중일 수 있음)은 제외하고 각 디렉터리의 고아를 모은다.
     *
     * Java 는 ArrayList 를 만들어 addAll 로 이어붙였으나, Kotlin 은 리스트를 `+` 로 이어 붙일 수 있어
     * 가변 리스트가 필요 없다.
     */
    fun scanOrphans(graceHours: Long): List<OrphanFileResponse> {
        val now = Instant.now()
        val threshold = now.minus(Duration.ofHours(graceHours))
        return scan(
            PRODUCT_IMAGES,
            referencedFilenames(adminProductImageRepository.findAllImageUrls()),
            now,
            threshold,
        ) +
            scan(
                REPORT_EVIDENCE,
                referencedFilenames(adminReportRepository.findAllEvidenceImageUrls()),
                now,
                threshold,
            )
    }

    /** 선택 파일을 삭제한다. 삭제 직전 "지금도 고아·grace 통과"인지 재확인해 참조/최근 파일을 보호한다. */
    fun deleteOrphans(
        request: OrphanDeleteRequest?,
        graceHours: Long,
    ): OrphanDeleteResponse {
        // Java 의 `(request == null || request.targets() == null) ? List.of() : request.targets()`
        // 삼항 + 두 번의 null 검사가 안전 호출 + 엘비스 한 줄로 합쳐진다.
        val targets = request?.targets ?: emptyList()
        targets.forEach { validateDirectory(it.directory) } // 잘못된 디렉터리는 삭제 전 일괄 거부

        val currentOrphans = scanOrphans(graceHours).map { key(it.directory, it.filename) }.toSet()

        var deleted = 0
        var skipped = 0
        for (target in targets) {
            if (key(target.directory, target.filename) !in currentOrphans) {
                skipped++ // 지금은 고아 아님(참조됨/최근/이미 없음) → 건너뜀
                continue
            }
            try {
                if (fileStorageService.delete(target.filename, target.directory)) {
                    deleted++
                } else {
                    skipped++ // 그 사이 이미 사라짐(멱등)
                }
            } catch (e: StorageException) {
                throw BusinessException(ErrorCode.STORAGE_ORPHAN_DELETE_FAILED)
            }
        }
        return OrphanDeleteResponse(targets.size, deleted, skipped)
    }

    private fun scan(
        directory: String,
        referenced: Set<String>,
        now: Instant,
        threshold: Instant,
    ): List<OrphanFileResponse> =
        fileStorageService
            .list(directory)
            .filter { it.filename !in referenced } // DB 미참조 = 고아 후보
            .filter { it.lastModified.isBefore(threshold) } // grace 통과(충분히 오래된 것만)
            .map {
                OrphanFileResponse(
                    directory,
                    it.filename,
                    it.sizeBytes,
                    it.lastModified,
                    Duration.between(it.lastModified, now).toHours(),
                )
            }

    /**
     * URL(/api/.../<filename>)에서 파일명만 뽑아 집합으로 — 마지막 '/' 뒤가 저장 파일명.
     *
     * 원본의 `url != null` 검사는 옮기지 않았다. ProductImage.imageUrl 은 `@Column(nullable = false)`
     * 이고 신고 쿼리는 `where evidenceImageUrl is not null` 이라 null 원소가 들어올 수 없다.
     */
    private fun referencedFilenames(urls: List<String>): Set<String> =
        urls
            .filter { it.isNotBlank() }
            .map { it.substring(it.lastIndexOf('/') + 1) }
            .toSet()

    private fun validateDirectory(directory: String) {
        if (directory != PRODUCT_IMAGES && directory != REPORT_EVIDENCE) {
            throw BusinessException(ErrorCode.INVALID_STORAGE_DIRECTORY)
        }
    }

    private fun key(
        directory: String,
        filename: String,
    ): String = "$directory#$filename"

    companion object {
        private const val PRODUCT_IMAGES = "product-images"
        private const val REPORT_EVIDENCE = "report-evidence"
    }
}
