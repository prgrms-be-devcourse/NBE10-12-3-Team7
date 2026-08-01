package com.dongnemarket.favorite.service

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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import java.util.Optional

/**
 * FavoriteService 단위 테스트.
 *
 * Repository·ProductService·EntityManager·ApplicationEventPublisher를 mock으로 대체하고
 * **서비스의 비자명 분기 로직만** 검증한다(성공 시 부수효과·이벤트 발행, 실패 시 차단, race condition 변환).
 * 단순 매핑/조회는 컨트롤러 통합 테스트(FavoriteControllerTest)가 실제 값으로 검증한다.
 */
@ExtendWith(MockitoExtension::class)
@DisplayName("FavoriteService 단위 테스트")
class FavoriteServiceTest {
    @Mock
    lateinit var favoriteRepository: FavoriteRepository

    @Mock
    lateinit var productService: ProductService

    @Mock
    lateinit var entityManager: EntityManager

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    @InjectMocks
    lateinit var favoriteService: FavoriteService

    @Nested
    @DisplayName("관심 등록")
    inner class Add {
        @Test
        fun `성공하면 관심 정보를 반환하고 FavoriteAddedEvent를 발행한다`() {
            val product = mock(Product::class.java)
            val seller = mock(Member::class.java)
            given(product.id).willReturn(PRODUCT_ID)
            given(product.member).willReturn(seller)
            given(seller.id).willReturn(SELLER_ID)
            given(favoriteRepository.existsByMember_IdAndProduct_Id(MEMBER_ID, PRODUCT_ID)).willReturn(false)
            given(entityManager.find(Member::class.java, MEMBER_ID)).willReturn(mock(Member::class.java))
            given(entityManager.find(Product::class.java, PRODUCT_ID)).willReturn(product)
            given(favoriteRepository.save(any(Favorite::class.java))).willAnswer { it.arguments[0] }

            val response = favoriteService.add(MEMBER_ID, PRODUCT_ID)

            assertThat(response.productId).isEqualTo(PRODUCT_ID)
            verify(eventPublisher).publishEvent(FavoriteAddedEvent(PRODUCT_ID))
        }

        @Test
        fun `접근 불가(존재하지 않거나 삭제·숨김) 상품이면 PRODUCT_NOT_FOUND, 저장·발행하지 않는다`() {
            willThrow(BusinessException(ErrorCode.PRODUCT_NOT_FOUND))
                .given(productService)
                .validateAccessibleProduct(PRODUCT_ID)

            assertThatThrownBy { favoriteService.add(MEMBER_ID, PRODUCT_ID) }
                .isInstanceOf(BusinessException::class.java)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRODUCT_NOT_FOUND)

            verify(favoriteRepository, never()).save(any())
            verify(eventPublisher, never()).publishEvent(any())
        }

        @Test
        fun `본인이 등록한 상품이면 CANNOT_FAVORITE_OWN_PRODUCT, 저장·발행하지 않는다`() {
            val product = mock(Product::class.java)
            val seller = mock(Member::class.java)
            given(entityManager.find(Product::class.java, PRODUCT_ID)).willReturn(product)
            given(product.member).willReturn(seller)
            given(seller.id).willReturn(MEMBER_ID) // 상품 소유자 == 등록 시도자

            assertThatThrownBy { favoriteService.add(MEMBER_ID, PRODUCT_ID) }
                .isInstanceOf(BusinessException::class.java)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CANNOT_FAVORITE_OWN_PRODUCT)

            verify(favoriteRepository, never()).save(any())
            verify(eventPublisher, never()).publishEvent(any())
        }

        @Test
        fun `이미 등록한 상품이면 FAVORITE_ALREADY_EXISTS, 저장·발행하지 않는다`() {
            val product = mock(Product::class.java)
            val seller = mock(Member::class.java)
            given(entityManager.find(Product::class.java, PRODUCT_ID)).willReturn(product)
            given(product.member).willReturn(seller)
            given(seller.id).willReturn(SELLER_ID)
            given(favoriteRepository.existsByMember_IdAndProduct_Id(MEMBER_ID, PRODUCT_ID)).willReturn(true)

            assertThatThrownBy { favoriteService.add(MEMBER_ID, PRODUCT_ID) }
                .isInstanceOf(BusinessException::class.java)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FAVORITE_ALREADY_EXISTS)

            verify(favoriteRepository, never()).save(any())
            verify(eventPublisher, never()).publishEvent(any())
        }

        @Test
        fun `중복 체크 통과 후 save() 시점에 UNIQUE 제약을 위반하면(race condition) FAVORITE_ALREADY_EXISTS로 변환한다`() {
            val product = mock(Product::class.java)
            val seller = mock(Member::class.java)
            given(product.member).willReturn(seller)
            given(seller.id).willReturn(SELLER_ID)
            given(favoriteRepository.existsByMember_IdAndProduct_Id(MEMBER_ID, PRODUCT_ID)).willReturn(false)
            given(entityManager.find(Member::class.java, MEMBER_ID)).willReturn(mock(Member::class.java))
            given(entityManager.find(Product::class.java, PRODUCT_ID)).willReturn(product)
            given(favoriteRepository.save(any(Favorite::class.java)))
                .willThrow(DataIntegrityViolationException("duplicate entry"))

            assertThatThrownBy { favoriteService.add(MEMBER_ID, PRODUCT_ID) }
                .isInstanceOf(BusinessException::class.java)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FAVORITE_ALREADY_EXISTS)

            // save 실패로 발행 지점에 도달하지 못하므로 이벤트도 나가지 않는다(카운트 무결).
            verify(eventPublisher, never()).publishEvent(any())
        }
    }

    @Nested
    @DisplayName("관심 취소")
    inner class Remove {
        @Test
        fun `성공하면 관심을 삭제하고 FavoriteRemovedEvent를 발행한다`() {
            val favorite = Favorite.of(mock(Member::class.java), mock(Product::class.java))
            given(favoriteRepository.findByMember_IdAndProduct_Id(MEMBER_ID, PRODUCT_ID))
                .willReturn(Optional.of(favorite))

            favoriteService.remove(MEMBER_ID, PRODUCT_ID)

            verify(favoriteRepository).delete(favorite)
            verify(eventPublisher).publishEvent(FavoriteRemovedEvent(PRODUCT_ID))
        }

        @Test
        fun `등록하지 않은 상품이면 FAVORITE_NOT_FOUND, 삭제·발행하지 않는다`() {
            given(favoriteRepository.findByMember_IdAndProduct_Id(MEMBER_ID, PRODUCT_ID))
                .willReturn(Optional.empty())

            assertThatThrownBy { favoriteService.remove(MEMBER_ID, PRODUCT_ID) }
                .isInstanceOf(BusinessException::class.java)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FAVORITE_NOT_FOUND)

            verify(favoriteRepository, never()).delete(any())
            verify(eventPublisher, never()).publishEvent(any())
        }
    }

    companion object {
        private const val MEMBER_ID = 1L
        private const val PRODUCT_ID = 100L
        private const val SELLER_ID = 2L // 상품 소유자(등록 시도자와 다른 사람)
    }
}
