package com.dongnemarket.admin.repository

import com.dongnemarket.product.entity.Product
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Admin 전용 상품 조회 Repository.
 * 팀원(product) Repository를 수정하지 않기 위해 admin 패키지에 별도로 둔다.
 * 관리자는 숨김(hidden)·삭제(deletedAt) 여부와 무관하게 전체 상품을 조회한다.
 */
interface AdminProductRepository : JpaRepository<Product, Long>
