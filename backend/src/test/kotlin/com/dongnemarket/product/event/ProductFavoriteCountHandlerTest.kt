package com.dongnemarket.product.event

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.global.common.event.FavoriteAddedEvent
import com.dongnemarket.global.common.event.FavoriteRemovedEvent
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * [통합] 찜 이벤트 → 상품 관심 수 반영.
 *
 * 이 경로는 **양쪽 끝만** 검증돼 있었다. 발행부는 `FavoriteServiceTest` 가
 * `verify(eventPublisher).publishEvent(FavoriteAddedEvent(PRODUCT_ID))` 로,
 * 실행부는 `ProductRepositoryTest` 가 `incrementFavoriteCount` 를 직접 호출해서 본다.
 * 정작 그 둘을 잇는 [ProductFavoriteCountHandler] 의 `@EventListener` 배선은 아무도 안 봤다.
 *
 * 어노테이션이 빠지거나 이벤트 타입이 어긋나도 **양쪽 테스트는 전부 통과한다.**
 * 찜은 저장되는데 화면의 관심 수만 안 오르는 상태가 되고, 예외도 로그도 남지 않는다.
 * 그래서 여기서는 mock 없이 실제 컨텍스트에 이벤트를 발행해 DB 값까지 확인한다.
 *
 * 클래스에 `@Transactional` 이 필요한 이유: `incrementFavoriteCount` 는 `@Modifying` 벌크
 * UPDATE 라 트랜잭션 안에서만 실행된다. 운영에서는 `FavoriteService` 의 트랜잭션이 그 역할을
 * 하지만(동기 리스너라 같은 트랜잭션에서 돈다), 테스트에서 직접 발행할 때는 경계가 없다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductFavoriteCountHandlerTest {
    @Autowired
    lateinit var eventPublisher: ApplicationEventPublisher

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var categoryRepository: CategoryRepository

    @Autowired
    lateinit var productRepository: ProductRepository

    @Autowired
    lateinit var regionRepository: RegionRepository

    @Test
    fun `찜 추가 이벤트가 발행되면 상품의 관심 수가 1 증가한다`() {
        val product = saveProduct("favorite-event-add@example.com", "관심수 증가 카테고리")
        assertThat(product.favoriteCount).isZero()

        eventPublisher.publishEvent(FavoriteAddedEvent(product.id!!))

        assertThat(reload(product).favoriteCount).isEqualTo(1)
    }

    @Test
    fun `찜 삭제 이벤트가 발행되면 상품의 관심 수가 1 감소한다`() {
        val product = saveProduct("favorite-event-remove@example.com", "관심수 감소 카테고리")
        eventPublisher.publishEvent(FavoriteAddedEvent(product.id!!))
        eventPublisher.publishEvent(FavoriteAddedEvent(product.id!!))
        assertThat(reload(product).favoriteCount).isEqualTo(2)

        eventPublisher.publishEvent(FavoriteRemovedEvent(product.id!!))

        // 리스너가 두 이벤트 타입에 각각 붙어 있다. 하나만 배선돼도 이 단언에서 걸린다.
        assertThat(reload(product).favoriteCount).isEqualTo(1)
    }

    private fun saveProduct(
        email: String,
        categoryName: String,
    ): Product {
        val member = memberRepository.save(Member.createUser(email, "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category(categoryName))
        return productRepository.saveAndFlush(
            Product.create(
                member,
                category,
                "관심수 검증 상품",
                "찜 이벤트 배선을 확인하는 상품입니다.",
                BigDecimal.valueOf(50000),
                saveDong(),
            ),
        )
    }

    /** `@Modifying(clearAutomatically = true)` 이 영속성 컨텍스트를 비우므로 다시 조회해야 갱신값이 보인다. */
    private fun reload(product: Product): Product =
        productRepository.findById(product.id!!).orElseThrow { IllegalStateException("상품이 사라졌다") }

    private fun saveDong(): Region {
        val seoul =
            regionRepository
                .findByCode("1100000000")
                .orElseGet { regionRepository.save(Region.root("1100000000", "서울특별시", "서울특별시")) }
        val gangnam =
            regionRepository
                .findByCode("1168000000")
                .orElseGet { regionRepository.save(Region.child("1168000000", 2, seoul, "서울특별시 강남구", "강남구")) }
        return regionRepository
            .findByCode("1168010100")
            .orElseGet { regionRepository.save(Region.child("1168010100", 3, gangnam, "서울특별시 강남구 역삼동", "역삼동")) }
    }
}
