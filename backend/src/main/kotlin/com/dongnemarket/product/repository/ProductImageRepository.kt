package com.dongnemarket.product.repository

import com.dongnemarket.product.entity.ProductImage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProductImageRepository : JpaRepository<ProductImage, Long> {
    fun findAllByProductIdOrderBySortOrderAsc(productId: Long): List<ProductImage>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ProductImage pi where pi.product.id = :productId")
    fun deleteAllByProductId(
        @Param("productId") productId: Long,
    )
}
