package com.dongnemarket.trade.service

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.chat.entity.ChatRoom
import com.dongnemarket.chat.repository.ChatRoomRepository
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.TradeStatus
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * [통합] TradeService — 별도 repository가 없는 도메인이라, 다른 도메인이 소유한
 * ProductRepository/ChatRoomRepository를 목킹하지 않고 실제 DB와 함께 연결이 맞는지 검증한다.
 * 클래스에 [Transactional]을 걸어 각 테스트가 만든 데이터가 종료 후 롤백되게 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TradeServiceIntegrationTest {
    @Autowired
    lateinit var tradeService: TradeService

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var productRepository: ProductRepository

    @Autowired
    lateinit var chatRoomRepository: ChatRoomRepository

    @Autowired
    lateinit var categoryRepository: CategoryRepository

    @Autowired
    lateinit var regionRepository: RegionRepository

    private fun saveTestRegion(): Region {
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

    private fun saveProduct(
        seller: Member,
        title: String,
        price: BigDecimal,
        tradeStatus: TradeStatus,
    ): Product {
        val category = categoryRepository.save(Category("거래내역통합테스트카테고리-$title"))
        val product = productRepository.save(Product.create(seller, category, title, "설명", price, saveTestRegion()))
        if (tradeStatus == TradeStatus.COMPLETED) {
            product.complete()
        }
        return product
    }

    @Test
    fun `판매내역은 실제 DB에서 거래완료 상품만 조회하고 지역 정보를 함께 담는다`() {
        val seller = memberRepository.save(Member.createUser("trade-it-seller@example.com", "pw", "판매자"))
        saveProduct(seller, "판매중 상품", BigDecimal.valueOf(10000), TradeStatus.ON_SALE)
        val completed = saveProduct(seller, "완료된 상품", BigDecimal.valueOf(20000), TradeStatus.COMPLETED)

        val sales = tradeService.getSales(seller.id!!)

        assertThat(sales).hasSize(1)
        assertThat(sales[0].productId).isEqualTo(completed.id)
        assertThat(sales[0].regionFullName).isEqualTo("서울특별시 강남구 역삼동")
    }

    @Test
    fun `구매내역은 실제 DB에서 내가 buyer인 방 중 상품이 거래완료된 것만 조회한다`() {
        val seller = memberRepository.save(Member.createUser("trade-it-seller2@example.com", "pw", "판매자"))
        val buyer = memberRepository.save(Member.createUser("trade-it-buyer@example.com", "pw", "구매자"))
        val completedProduct = saveProduct(seller, "구매완료 상품", BigDecimal.valueOf(15000), TradeStatus.COMPLETED)
        val notCompletedProduct = saveProduct(seller, "미완료 상품", BigDecimal.valueOf(15000), TradeStatus.ON_SALE)
        chatRoomRepository.save(ChatRoom.of(completedProduct, buyer, seller))
        chatRoomRepository.save(ChatRoom.of(notCompletedProduct, buyer, seller))

        val purchases = tradeService.getPurchases(buyer.id!!)

        assertThat(purchases).hasSize(1)
        assertThat(purchases[0].productId).isEqualTo(completedProduct.id)
        assertThat(purchases[0].sellerNickname).isEqualTo(seller.displayNickname)
    }

    @Test
    fun `월별 통계는 실제 DB에서 판매와 구매를 함께 집계한다`() {
        val seller = memberRepository.save(Member.createUser("trade-it-seller3@example.com", "pw", "판매자"))
        val buyer = memberRepository.save(Member.createUser("trade-it-buyer3@example.com", "pw", "구매자"))
        val sale = saveProduct(seller, "이번달 판매", BigDecimal.valueOf(30000), TradeStatus.COMPLETED)
        val purchasedProduct = saveProduct(seller, "이번달 구매", BigDecimal.valueOf(7000), TradeStatus.COMPLETED)
        chatRoomRepository.save(ChatRoom.of(purchasedProduct, buyer, seller))

        val sellerStats = tradeService.getMonthlyStats(seller.id!!)
        val buyerStats = tradeService.getMonthlyStats(buyer.id!!)

        assertThat(sellerStats).hasSize(1)
        assertThat(sellerStats[0].salesCount).isEqualTo(2) // sale + purchasedProduct 둘 다 seller가 판매자
        assertThat(sellerStats[0].salesAmount).isEqualByComparingTo(sale.price.add(purchasedProduct.price))

        assertThat(buyerStats).hasSize(1)
        assertThat(buyerStats[0].purchasesCount).isEqualTo(1)
        assertThat(buyerStats[0].purchasesAmount).isEqualByComparingTo(purchasedProduct.price)
    }
}
