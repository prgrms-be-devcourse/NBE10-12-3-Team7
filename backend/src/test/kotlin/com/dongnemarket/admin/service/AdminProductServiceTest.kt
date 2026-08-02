package com.dongnemarket.admin.service

import com.dongnemarket.admin.repository.AdminProductRepository
import com.dongnemarket.category.entity.Category
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.TradeStatus
import com.dongnemarket.region.entity.Region
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.test.util.ReflectionTestUtils
import java.math.BigDecimal
import java.util.Optional

/**
 * [단위] AdminProductService — 두 변이(hide/delete)와 공유 NOT_FOUND만 검증.
 *  - 제외: getProducts/getProduct 위임(통합), "이미 삭제·숨김 상품 재처리"(AD-27 갭)는 findById 동작(리포지토리/통합).
 *  - member·category는 hide/delete와 무관하므로 null 픽스처 사용.
 */
@ExtendWith(MockitoExtension::class)
class AdminProductServiceTest {
    @Mock
    lateinit var adminProductRepository: AdminProductRepository

    @InjectMocks
    lateinit var adminProductService: AdminProductService

    /** Product.create 는 아직 Java 라 파라미터가 플랫폼 타입이다 → Kotlin 에서도 null 을 그대로 넘길 수 있다. */
    // [변경 · product Kotlin 전환] 위 전제가 더는 성립하지 않는다. Product.create 가 Kotlin 이 되어
    // 파라미터가 non-null 이므로 null 대신 최소 인스턴스를 넣는다(products 의 member_id·category_id 는
    // nullable = false). 삭제 로직만 검증하므로 판매자·카테고리의 내용은 여전히 쓰지 않는다.
    private fun existingProduct(): Product =
        Product.create(
            Member.createUser("admin-product@example.com", "encodedPassword", "판매자"),
            Category("부적절상품카테고리"),
            "부적절 상품",
            "설명",
            BigDecimal("10000"),
            yeoksam(),
        )

    @Nested
    @DisplayName("성공 케이스")
    inner class Success {
        @Test
        fun `상품을 숨기면 hidden=true가 된다`() {
            val product = existingProduct()
            given(adminProductRepository.findById(1L)).willReturn(Optional.of(product))

            adminProductService.hideProduct(1L)

            assertThat(product.isHidden).isTrue()
        }

        @Test
        fun `상품을 삭제하면 softDelete 되어 deletedAt이 기록된다`() {
            val product = existingProduct()
            given(adminProductRepository.findById(1L)).willReturn(Optional.of(product))

            adminProductService.deleteProduct(1L)

            assertThat(product.isDeleted).isTrue()
            assertThat(product.deletedAt).isNotNull()
        }

        @Test
        fun `관리자 상품 목록은 탈퇴·정지 판매자 상품과 거래완료 상품도 반환한다`() {
            val deletedSellerProduct = product(1L, "탈퇴 판매자 상품", MemberStatus.DELETED, TradeStatus.ON_SALE)
            val suspendedSellerProduct = product(2L, "정지 판매자 상품", MemberStatus.SUSPENDED, TradeStatus.ON_SALE)
            val completedProduct = product(3L, "거래완료 상품", MemberStatus.ACTIVE, TradeStatus.COMPLETED)
            given(adminProductRepository.findAll())
                .willReturn(listOf(deletedSellerProduct, suspendedSellerProduct, completedProduct))

            val responses = adminProductService.getProducts()

            assertThat(responses.map { it.title })
                .containsExactly("탈퇴 판매자 상품", "정지 판매자 상품", "거래완료 상품")
            assertThat(responses.map { it.tradeStatus })
                .containsExactly(TradeStatus.ON_SALE, TradeStatus.ON_SALE, TradeStatus.COMPLETED)
        }

        @Test
        fun `관리자 상품 상세는 탈퇴 판매자 상품과 거래완료 상품도 반환한다`() {
            val completedProduct = product(1L, "거래완료 상품", MemberStatus.DELETED, TradeStatus.COMPLETED)
            given(adminProductRepository.findById(1L)).willReturn(Optional.of(completedProduct))

            val response = adminProductService.getProduct(1L)

            assertThat(response.title).isEqualTo("거래완료 상품")
            assertThat(response.tradeStatus).isEqualTo(TradeStatus.COMPLETED)
            assertThat(response.regionCode).isEqualTo("1168010100")
            assertThat(response.regionName).isEqualTo("역삼동")
            assertThat(response.regionFullName).isEqualTo("서울특별시 강남구 역삼동")
        }
    }

    @Nested
    @DisplayName("실패 케이스")
    inner class Failure {
        @Test
        fun `없는 상품을 숨기거나 삭제하면 PRODUCT_NOT_FOUND 예외가 발생한다`() {
            given(adminProductRepository.findById(999L)).willReturn(Optional.empty())

            val ex = assertThrows<BusinessException> { adminProductService.hideProduct(999L) }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND)
        }
    }

    private fun product(
        id: Long,
        title: String,
        memberStatus: MemberStatus,
        tradeStatus: TradeStatus,
    ): Product {
        val member = Member.createUser("admin-product-$id@example.com", "encodedPassword", "판매자$id")
        member.changeStatus(memberStatus)
        ReflectionTestUtils.setField(member, "id", id)
        val category = Category("관리자상품카테고리$id")
        ReflectionTestUtils.setField(category, "id", id)
        val product = Product.create(member, category, title, "설명", BigDecimal("10000"), yeoksam())
        ReflectionTestUtils.setField(product, "id", id)
        product.changeTradeStatus(tradeStatus)
        return product
    }

    private fun yeoksam(): Region {
        val seoul = Region.root("1100000000", "서울특별시", "서울특별시")
        val gangnam = Region.child("1168000000", 2, seoul, "서울특별시 강남구", "강남구")
        return Region.child("1168010100", 3, gangnam, "서울특별시 강남구 역삼동", "역삼동")
    }
}
