package com.dongnemarket.admin.service

import com.dongnemarket.admin.dto.AdminProductResponse
import com.dongnemarket.admin.repository.AdminProductRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AdminProductService(
    private val adminProductRepository: AdminProductRepository,
) {
    /** 전체 상품 목록 (숨김·삭제 무관) */
    fun getProducts(): List<AdminProductResponse> = adminProductRepository.findAll().map(AdminProductResponse::from)

    /** 상품 단건 상세 */
    fun getProduct(productId: Long): AdminProductResponse {
        val product =
            adminProductRepository
                .findById(productId)
                .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }
        return AdminProductResponse.from(product)
    }

    /** 상품 숨김 처리 (관리자는 작성자가 아니어도 가능, 한방향) */
    @Transactional
    fun hideProduct(productId: Long) {
        val product =
            adminProductRepository
                .findById(productId)
                .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }
        product.hide()
    }

    /** 상품 소프트 삭제 (관리자) */
    @Transactional
    fun deleteProduct(productId: Long) {
        val product =
            adminProductRepository
                .findById(productId)
                .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }
        product.softDelete()
    }
}
