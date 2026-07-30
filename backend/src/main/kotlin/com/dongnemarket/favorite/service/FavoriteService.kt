package com.dongnemarket.favorite.service

import com.dongnemarket.favorite.dto.FavoriteResponse
import com.dongnemarket.favorite.dto.MyFavoriteResponse
import com.dongnemarket.favorite.entity.Favorite
import com.dongnemarket.favorite.repository.FavoriteRepository
import com.dongnemarket.global.common.event.FavoriteAddedEvent
import com.dongnemarket.global.common.event.FavoriteRemovedEvent
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.entity.Member
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.service.ProductService
import jakarta.persistence.EntityManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class FavoriteService(
    private val favoriteRepository: FavoriteRepository,
    private val productService: ProductService,
    private val entityManager: EntityManager,
    private val eventPublisher: ApplicationEventPublisher,
) {
    /** 관심 상품 등록. 로그인 사용자가 특정 상품을 관심 목록에 추가한다. */
    @Transactional
    fun add(
        memberId: Long,
        productId: Long,
    ): FavoriteResponse {
        val product = validateFavoriteCreatable(memberId, productId)

        val member = entityManager.find(Member::class.java, memberId)
        val saved =
            try {
                favoriteRepository.save(Favorite.of(member, product))
            } catch (e: DataIntegrityViolationException) {
                // 중복 체크 통과 후 save() 사이의 race condition으로 UNIQUE 제약을 위반한 경우
                throw BusinessException(ErrorCode.FAVORITE_ALREADY_EXISTS)
            }
        // 같은 트랜잭션 내 동기 리스너가 Product.favoriteCount를 1 증가시킨다(무결성 보장).
        // 발행은 catch 밖에 두어, 리스너 예외가 race condition으로 오분류되지 않게 한다.
        eventPublisher.publishEvent(FavoriteAddedEvent(productId))
        return FavoriteResponse.from(saved)
    }

    /**
     * 내 관심 상품 목록 조회. 로그인 사용자가 등록한 관심 상품을 상품 요약과 함께 최근 등록순으로 조회한다.
     * 삭제·숨김된 상품의 관심은 목록에서 제외한다(정책 A).
     *
     * 상품을 fetch join으로 함께 로딩해 N+1을 제거하고, [MY_FAVORITES_LIMIT]로 상한을 둔다.
     * 페이지네이션은 클라이언트에서 처리한다(개인 목록이라 바운드됨).
     */
    fun getMyFavorites(memberId: Long): List<MyFavoriteResponse> =
        favoriteRepository
            .findMyFavoritesWithProduct(memberId, MY_FAVORITES_LIMIT)
            .map { MyFavoriteResponse.from(it) }

    /** 특정 상품을 관심 등록한 회원 id들(판매자 본인 제외). 가격 변경 알림 수신자 조회용. */
    fun findFavoriteMemberIdsForProduct(productId: Long): List<Long> = favoriteRepository.findFavoriteMemberIdsForProduct(productId)

    /** 관심 상품 취소. 로그인 사용자가 자신이 등록한 관심 상품을 제거한다. */
    @Transactional
    fun remove(
        memberId: Long,
        productId: Long,
    ) {
        val favorite =
            favoriteRepository
                .findByMember_IdAndProduct_Id(memberId, productId)
                .orElseThrow { BusinessException(ErrorCode.FAVORITE_NOT_FOUND) }
        favoriteRepository.delete(favorite)
        // 같은 트랜잭션 내 동기 리스너가 Product.favoriteCount를 1 감소시킨다(무결성 보장).
        eventPublisher.publishEvent(FavoriteRemovedEvent(productId))
    }

    /**
     * 관심 등록 가능 여부를 검증하고 대상 상품을 반환한다: 접근 가능한 상품(삭제·숨김 아님)이어야 하고,
     * 본인이 등록한 상품이 아니어야 하며, 동일 사용자가 이미 등록하지 않았어야 한다.
     * 반환한 상품은 호출부가 재조회 없이 그대로 저장에 재사용한다.
     */
    private fun validateFavoriteCreatable(
        memberId: Long,
        productId: Long,
    ): Product {
        productService.validateAccessibleProduct(productId)
        val product = entityManager.find(Product::class.java, productId)
        // 판매자 본인은 자기 상품을 관심 등록할 수 없다(프록시 id 접근이라 추가 쿼리 없음).
        if (product.member.id == memberId) {
            throw BusinessException(ErrorCode.CANNOT_FAVORITE_OWN_PRODUCT)
        }
        if (favoriteRepository.existsByMember_IdAndProduct_Id(memberId, productId)) {
            throw BusinessException(ErrorCode.FAVORITE_ALREADY_EXISTS)
        }
        return product
    }

    companion object {
        /** 관심 목록 조회 상한. 개인 목록은 자연히 바운드되지만, 비정상 폭주 시 payload·메모리를 캡한다(최근순 기준). */
        private val MY_FAVORITES_LIMIT = Limit.of(200)
    }
}
