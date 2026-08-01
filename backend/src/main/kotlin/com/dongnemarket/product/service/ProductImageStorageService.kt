package com.dongnemarket.product.service

import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.storage.FileStorageService
import com.dongnemarket.global.storage.StorageException
import com.dongnemarket.global.storage.StorageFileNotFoundException
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

/**
 * 상품 이미지 검증 + 저장을 담당한다. 실제 저장/조회는 [FileStorageService](global/storage)에 위임하는
 * 얇은 어댑터다 — 저장 백엔드(로컬 디스크/S3)는 `file.storage.type` 설정으로 갈리며 이 클래스는 그 차이를 모른다.
 * 검증 로직·에러코드 등 product 도메인 정책은 이 클래스가 그대로 소유한다.
 */
@Service
class ProductImageStorageService(
    private val fileStorageService: FileStorageService,
) {
    fun store(files: List<MultipartFile>?): List<String> {
        validateFiles(files)
        return files!!.map { PRODUCT_IMAGE_URL_PREFIX + storeOne(it) }
    }

    fun load(filename: String): Resource =
        try {
            fileStorageService.load(filename, DIRECTORY)
        } catch (e: StorageFileNotFoundException) {
            throw BusinessException(ErrorCode.PRODUCT_NOT_FOUND)
        }

    private fun storeOne(file: MultipartFile): String =
        try {
            fileStorageService.store(file, DIRECTORY)
        } catch (e: StorageException) {
            throw BusinessException(ErrorCode.INVALID_INPUT_VALUE)
        }

    private fun validateFiles(files: List<MultipartFile>?) {
        if (files.isNullOrEmpty() || files.size > MAX_IMAGE_COUNT) {
            throw BusinessException(ErrorCode.INVALID_INPUT_VALUE)
        }
        files.forEach { validateFile(it) }
    }

    private fun validateFile(file: MultipartFile?) {
        if (file == null || file.isEmpty) {
            throw BusinessException(ErrorCode.INVALID_INPUT_VALUE)
        }
        if (file.size > MAX_FILE_SIZE) {
            throw BusinessException(ErrorCode.INVALID_INPUT_VALUE)
        }
        val contentType = file.contentType
        if (contentType == null || contentType !in ALLOWED_CONTENT_TYPES) {
            throw BusinessException(ErrorCode.INVALID_INPUT_VALUE)
        }
    }

    companion object {
        private const val DIRECTORY = "product-images"
        private const val PRODUCT_IMAGE_URL_PREFIX = "/api/products/images/"
        private const val MAX_IMAGE_COUNT = 5
        private const val MAX_FILE_SIZE = 5L * 1024 * 1024
        private val ALLOWED_CONTENT_TYPES =
            setOf("image/jpeg", "image/png", "image/gif", "image/webp")
    }
}
