package com.dongnemarket.report.service

import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.storage.FileStorageService
import com.dongnemarket.global.storage.StorageException
import com.dongnemarket.global.storage.StorageFileNotFoundException
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

/**
 * 신고 증빙 이미지 검증 + 저장을 담당한다. 실제 저장/조회는 [FileStorageService](global/storage)에 위임하는
 * 얇은 어댑터다 — 저장 백엔드(로컬 디스크/S3)는 `file.storage.type` 설정으로 갈리며 이 클래스는 그 차이를 모른다.
 * 검증 로직·에러코드 등 report 도메인 정책은 이 클래스가 그대로 소유한다.
 */
@Service
class EvidenceImageStorageService(
    private val fileStorageService: FileStorageService,
) {
    fun store(file: MultipartFile?): String {
        validate(file)
        return try {
            fileStorageService.store(file!!, DIRECTORY)
        } catch (e: StorageException) {
            throw BusinessException(ErrorCode.EVIDENCE_IMAGE_UPLOAD_FAILED)
        }
    }

    fun load(filename: String): Resource =
        try {
            fileStorageService.load(filename, DIRECTORY)
        } catch (e: StorageFileNotFoundException) {
            throw BusinessException(ErrorCode.EVIDENCE_IMAGE_NOT_FOUND)
        }

    private fun validate(file: MultipartFile?) {
        if (file == null || file.isEmpty) {
            throw BusinessException(ErrorCode.INVALID_EVIDENCE_IMAGE)
        }
        if (file.size > MAX_FILE_SIZE) {
            throw BusinessException(ErrorCode.INVALID_EVIDENCE_IMAGE)
        }
        val contentType = file.contentType
        if (contentType == null || contentType !in ALLOWED_CONTENT_TYPES) {
            throw BusinessException(ErrorCode.INVALID_EVIDENCE_IMAGE)
        }
    }

    companion object {
        private const val DIRECTORY = "report-evidence"
        private val ALLOWED_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
        private const val MAX_FILE_SIZE = 5L * 1024 * 1024
    }
}
