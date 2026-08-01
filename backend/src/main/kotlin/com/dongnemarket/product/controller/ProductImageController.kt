package com.dongnemarket.product.controller

import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.response.ApiResponse
import com.dongnemarket.product.dto.ProductImageUploadResponse
import com.dongnemarket.product.service.ProductImageStorageService
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.net.URLConnection

@RestController
class ProductImageController(
    private val productImageStorageService: ProductImageStorageService,
) {
    /** `memberId` 가 `Long?` 인 이유: 아래 명시적 null 검사가 원본에 있어 시그니처를 보존한다. */
    @PostMapping("/api/products/images", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadProductImages(
        @AuthenticationPrincipal memberId: Long?,
        @RequestPart("files") files: List<MultipartFile>,
    ): ResponseEntity<ApiResponse<ProductImageUploadResponse>> {
        if (memberId == null) {
            throw BusinessException(ErrorCode.UNAUTHORIZED)
        }
        val response = ProductImageUploadResponse.of(productImageStorageService.store(files))
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(HttpStatus.CREATED.value(), "상품 이미지가 업로드되었습니다.", response))
    }

    @GetMapping("/api/products/images/{filename}")
    fun getProductImage(
        @PathVariable filename: String,
    ): ResponseEntity<Resource> {
        val resource = productImageStorageService.load(filename)
        val contentType = URLConnection.guessContentTypeFromName(filename)
        val mediaType = if (contentType != null) MediaType.parseMediaType(contentType) else MediaType.APPLICATION_OCTET_STREAM
        return ResponseEntity
            .ok()
            .contentType(mediaType)
            .body(resource)
    }
}
