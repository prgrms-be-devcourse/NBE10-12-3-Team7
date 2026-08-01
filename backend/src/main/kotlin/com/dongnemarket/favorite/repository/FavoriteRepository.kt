package com.dongnemarket.favorite.repository

import com.dongnemarket.favorite.entity.Favorite
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface FavoriteRepository : JpaRepository<Favorite, Long> {
    fun existsByMember_IdAndProduct_Id(
        memberId: Long,
        productId: Long,
    ): Boolean

    fun findByMember_IdAndProduct_Id(
        memberId: Long,
        productId: Long,
    ): Optional<Favorite>

    /**
     * 내 관심 목록을 상품·카테고리와 함께 최근 등록순으로 조회한다. 삭제(deleted_at)·숨김(hidden) 상품은 제외한다(정책 A).
     *
     * `JOIN FETCH` 로 상품·카테고리를 한 번의 쿼리에 함께 로딩해, 목록 매핑 시 발생하는 N+1을 제거한다.
     * 상품·카테고리 모두 `@ManyToOne` 단건 연관이라 행 증식이 없어, `limit` 이 SQL 레벨에서 적용된다
     * (컬렉션 fetch join 의 메모리 페이징 문제 HHH000104 해당 없음).
     */
    @Query(
        "SELECT f FROM Favorite f " +
            "JOIN FETCH f.product p " +
            "JOIN FETCH p.category " +
            "WHERE f.member.id = :memberId " +
            "AND p.deletedAt IS NULL AND p.isHidden = false " +
            "ORDER BY f.createdAt DESC, f.id DESC",
    )
    fun findMyFavoritesWithProduct(
        @Param("memberId") memberId: Long,
        limit: Limit,
    ): List<Favorite>

    /**
     * 특정 상품을 관심 등록한 회원 id들. 가격 변경 알림 수신자(그 상품에 관심 있는 사용자) 조회용.
     *
     * 판매자 본인은 제외한다(`f.member.id <> f.product.member.id`). 자기 상품 찜이 현재 가능하므로
     * 이 제외 조건은 **필수**다(가격 변경 주체인 판매자에게 자기 알림이 가지 않게 함) — 무심코 제거하면 안 된다.
     */
    @Query(
        "SELECT f.member.id FROM Favorite f " +
            "WHERE f.product.id = :productId AND f.member.id <> f.product.member.id",
    )
    fun findFavoriteMemberIdsForProduct(
        @Param("productId") productId: Long,
    ): List<Long>
}
