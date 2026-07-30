package com.dongnemarket.admin.repository

import com.dongnemarket.product.entity.ProductImage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * Admin 전용 — 고아 대조에 쓸 product_images 참조 URL 조회.
 * 팀원(product) Repository를 수정하지 않기 위해 admin 패키지에 둔다.
 */
interface AdminProductImageRepository : JpaRepository<ProductImage, Long> {
    @Query("select pi.imageUrl from ProductImage pi")
    fun findAllImageUrls(): List<String>
}
