package com.dongnemarket.notification.service

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.chat.entity.ChatRoom
import com.dongnemarket.chat.repository.ChatRoomRepository
import com.dongnemarket.favorite.entity.Favorite
import com.dongnemarket.favorite.repository.FavoriteRepository
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.notification.dto.NotificationFeedType
import com.dongnemarket.notification.entity.NotificationType
import com.dongnemarket.notification.repository.NotificationRepository
import com.dongnemarket.product.dto.ProductUpdateRequest
import com.dongnemarket.product.repository.ProductImageRepository
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.product.service.ProductService
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal

/**
 * 가격 변경 알림 통합 테스트.
 *
 * 실제 `ProductService.updateProduct` 를 호출해 발행 → 핸들러(AFTER_COMMIT) → 알림 저장까지의 전 과정을 검증한다.
 * 테스트 클래스에 `@Transactional` 을 걸지 않아 updateProduct 가 실제 커밋되고 AFTER_COMMIT 리스너가 발동한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("가격 변경 알림 통합 테스트")
class NotificationPriceChangeTest {
    @Autowired
    lateinit var productService: ProductService

    @Autowired
    lateinit var notificationService: NotificationService

    @Autowired
    lateinit var notificationRepository: NotificationRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var categoryRepository: CategoryRepository

    @Autowired
    lateinit var productRepository: ProductRepository

    @Autowired
    lateinit var productImageRepository: ProductImageRepository

    @Autowired
    lateinit var regionRepository: RegionRepository

    @Autowired
    lateinit var chatRoomRepository: ChatRoomRepository

    @Autowired
    lateinit var favoriteRepository: FavoriteRepository

    private lateinit var seller: Member
    private lateinit var buyer: Member
    private var productId = 0L
    private var categoryId = 0L
    private lateinit var region: Region
    private lateinit var parentRegion: Region
    private lateinit var rootRegion: Region

    @BeforeEach
    fun setUp() {
        seller = memberRepository.save(Member.createUser("seller@example.com", "encoded-pw", "seller"))
        buyer = memberRepository.save(Member.createUser("buyer@example.com", "encoded-pw", "buyer"))
        val category = categoryRepository.save(Category("가격알림테스트전용카테고리"))
        rootRegion = regionRepository.save(Region.root("9999900100", "알림가격테스트시", "알림가격테스트시"))
        parentRegion =
            regionRepository.save(
                Region.child("9999900200", 2, rootRegion, "알림가격테스트시 알림가격테스트구", "알림가격테스트구"),
            )
        region =
            regionRepository.save(
                Region.child(REGION_CODE, 3, parentRegion, "알림가격테스트시 알림가격테스트구 알림가격테스트동", "알림가격테스트동"),
            )
        val product =
            productRepository.save(
                com.dongnemarket.product.entity.Product
                    .create(seller, category, "맥북 프로", "상태 좋음", BigDecimal.valueOf(1_500_000), region),
            )
        categoryId = category.id!!
        productId = product.id!!
    }

    @AfterEach
    fun cleanUp() {
        notificationRepository.deleteAll()
        chatRoomRepository.deleteAll()
        // Favorite은 product·member를 FK로 참조하므로 그들보다 먼저 정리한다.
        favoriteRepository.deleteAll()
        productImageRepository.deleteAll()
        productRepository.deleteAll()
        memberRepository.deleteAll()
        categoryRepository.deleteById(categoryId)
        regionRepository.delete(region)
        regionRepository.delete(parentRegion)
        regionRepository.delete(rootRegion)
    }

    /** 구매자가 이 상품에 채팅방을 연다(가격 알림 수신 대상이 된다). */
    private fun openRoom(roomBuyer: Member) {
        chatRoomRepository.save(ChatRoom.of(productRepository.findById(productId).orElseThrow(), roomBuyer, seller))
    }

    /** 회원이 이 상품을 관심 등록한다(가격 알림 수신 대상이 된다). */
    private fun addFavorite(favoriteMember: Member) {
        favoriteRepository.save(Favorite.of(favoriteMember, productRepository.findById(productId).orElseThrow()))
    }

    /** 판매자가 상품 가격을 바꾼다(가격만 변경). */
    private fun updatePriceTo(newPrice: BigDecimal) {
        val request =
            ProductUpdateRequest(
                categoryId,
                "맥북 프로",
                "상태 좋음",
                newPrice,
                REGION_CODE,
                listOf("https://img.example/mac.jpg"),
                0,
            )
        productService.updateProduct(seller.id!!, productId, request)
    }

    private fun unreadPriceNotifications(recipientId: Long): Int =
        notificationRepository
            .findByRecipient_IdAndProductIdAndTypeAndIsReadFalseOrderByLastNotifiedAtDesc(
                recipientId,
                productId,
                NotificationType.PRICE_CHANGE,
            ).size

    @Test
    fun `채팅방을 연 구매자가 있으면 가격 변경 시 그 구매자에게 알림이 저장된다`() {
        openRoom(buyer)

        updatePriceTo(BigDecimal.valueOf(1_000_000))

        val buyerNotis =
            notificationRepository
                .findByRecipient_IdAndProductIdAndTypeAndIsReadFalseOrderByLastNotifiedAtDesc(
                    buyer.id!!,
                    productId,
                    NotificationType.PRICE_CHANGE,
                )
        assertThat(buyerNotis).hasSize(1)
        assertThat(buyerNotis[0].message).contains("맥북 프로").contains("가격")
        // 저장된 PRICE_CHANGE 가 피드에도 PRICE_CHANGE 타입으로 노출되는지(from() 매핑) 확인.
        assertThat(notificationService.getMyNotifications(buyer.id!!))
            .anyMatch { it.type == NotificationFeedType.PRICE_CHANGE }
    }

    @Test
    fun `가격이 그대로면 알림이 생기지 않는다`() {
        openRoom(buyer)

        updatePriceTo(BigDecimal.valueOf(1_500_000)) // 기존과 동일 가격

        assertThat(unreadPriceNotifications(buyer.id!!)).isZero()
    }

    @Test
    fun `채팅방을 연 구매자가 여럿이면 각각에게 알림이 저장된다`() {
        val buyer2 = memberRepository.save(Member.createUser("buyer2@example.com", "encoded-pw", "buyer2"))
        openRoom(buyer)
        openRoom(buyer2)

        updatePriceTo(BigDecimal.valueOf(1_000_000))

        assertThat(unreadPriceNotifications(buyer.id!!)).isEqualTo(1)
        assertThat(unreadPriceNotifications(buyer2.id!!)).isEqualTo(1)
    }

    @Test
    fun `채팅방이 없는 구매자에겐 알림이 가지 않는다`() {
        // buyer는 채팅방을 열지 않음
        updatePriceTo(BigDecimal.valueOf(1_000_000))

        assertThat(unreadPriceNotifications(buyer.id!!)).isZero()
    }

    @Test
    fun `가격이 여러 번 바뀌어도 안읽은 알림은 하나로 합쳐진다(코얼레싱)`() {
        openRoom(buyer)

        updatePriceTo(BigDecimal.valueOf(1_000_000))
        updatePriceTo(BigDecimal.valueOf(900_000))

        assertThat(unreadPriceNotifications(buyer.id!!)).isEqualTo(1)
    }

    @Test
    fun `채팅방 없이 관심 등록만 한 사용자도 가격 변경 시 알림을 받는다`() {
        val favoriteUser = memberRepository.save(Member.createUser("fav@example.com", "encoded-pw", "fav"))
        addFavorite(favoriteUser) // 채팅방은 열지 않음

        updatePriceTo(BigDecimal.valueOf(1_000_000))

        assertThat(unreadPriceNotifications(favoriteUser.id!!)).isEqualTo(1)
    }

    @Test
    fun `판매자가 자기 상품을 관심 등록했어도 자기 가격 변경 알림은 받지 않는다`() {
        addFavorite(seller) // 자기 상품 찜(현재 가능) — 쿼리에서 판매자 제외가 load-bearing

        updatePriceTo(BigDecimal.valueOf(1_000_000))

        assertThat(unreadPriceNotifications(seller.id!!)).isZero()
    }

    @Test
    fun `채팅방도 열고 관심 등록도 한 사용자는 알림이 정확히 1건만 저장된다(합집합·코얼레싱)`() {
        openRoom(buyer)
        addFavorite(buyer)

        updatePriceTo(BigDecimal.valueOf(1_000_000))

        assertThat(unreadPriceNotifications(buyer.id!!)).isEqualTo(1)
    }

    companion object {
        private const val REGION_CODE = "9999900300"
    }
}
