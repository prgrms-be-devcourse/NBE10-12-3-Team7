package com.dongnemarket.product.repository

import com.dongnemarket.product.entity.Product
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProductRepository :
    JpaRepository<Product, Long>,
    JpaSpecificationExecutor<Product> {
    /**
     * 삭제되지 않고 숨김 처리되지 않은 상품을 최신 등록순으로 조회한다.
     *
     * 메서드 이름의 `IsHidden` 은 엔티티 프로퍼티명이다. Java 원본의 `isHidden()` 게터를
     * 유지하려고 Kotlin 프로퍼티를 `isHidden` 으로 지었기 때문이며, DB 컬럼은
     * `@Column(name = "hidden")` 으로 그대로 `hidden` 이다.
     */
    fun findAllByDeletedAtIsNullAndIsHiddenFalseOrderByIdDesc(): List<Product>

    // 삭제되지 않고 숨김 처리되지 않은 상품 존재 여부를 확인한다.
    fun existsByIdAndDeletedAtIsNullAndIsHiddenFalse(id: Long): Boolean

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Product p set p.favoriteCount = p.favoriteCount + 1 where p.id = :id")
    fun incrementFavoriteCount(
        @Param("id") id: Long,
    )

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Product p set p.favoriteCount = p.favoriteCount - 1 where p.id = :id and p.favoriteCount > 0")
    fun decrementFavoriteCount(
        @Param("id") id: Long,
    )

    // 특정 회원의 삭제되지 않은 상품을 최신 등록순으로 조회한다.
    fun findAllByMemberIdAndDeletedAtIsNullOrderByIdDesc(memberId: Long): List<Product>
}
