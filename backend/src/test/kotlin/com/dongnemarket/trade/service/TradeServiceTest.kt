package com.dongnemarket.trade.service

import com.dongnemarket.category.entity.Category
import com.dongnemarket.chat.entity.ChatRoom
import com.dongnemarket.chat.repository.ChatRoomRepository
import com.dongnemarket.member.entity.Member
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.test.util.ReflectionTestUtils
import java.math.BigDecimal
import java.time.LocalDateTime

/** [단위] TradeService — 판매/구매내역 필터링·정렬, 월별 통계 집계를 검증한다. */
@ExtendWith(MockitoExtension::class)
class TradeServiceTest {
    @Mock
    lateinit var productRepository: ProductRepository

    @Mock
    lateinit var chatRoomRepository: ChatRoomRepository

    @InjectMocks
    lateinit var tradeService: TradeService

    private fun daysAgo(days: Long): LocalDateTime = LocalDateTime.now().minusDays(days)

    private fun atMonth(
        year: Int,
        month: Int,
    ): LocalDateTime = LocalDateTime.of(year, month, 15, 0, 0)

    private fun member(id: Long): Member {
        val member = Member.createUser("member$id@example.com", "encodedPassword", "회원$id")
        ReflectionTestUtils.setField(member, "id", id)
        return member
    }

    private fun product(
        id: Long,
        title: String,
        price: BigDecimal,
        completedAt: LocalDateTime?,
    ): Product {
        val product = Product.create(member(ME_ID), Category("디지털기기"), title, "설명", price, yeoksam())
        ReflectionTestUtils.setField(product, "id", id)
        if (completedAt != null) {
            ReflectionTestUtils.setField(product, "completedAt", completedAt)
        }
        return product
    }

    private fun completedProduct(
        id: Long,
        title: String,
        price: BigDecimal,
        completedAt: LocalDateTime?,
    ): Product {
        val product = product(id, title, price, null)
        product.complete()
        ReflectionTestUtils.setField(product, "completedAt", completedAt)
        return product
    }

    private fun chatRoom(
        id: Long,
        product: Product,
        buyerId: Long,
        sellerId: Long,
    ): ChatRoom {
        val room = ChatRoom.of(product, member(buyerId), member(sellerId))
        ReflectionTestUtils.setField(room, "id", id)
        return room
    }

    private fun yeoksam(): Region {
        val seoul = Region.root("1100000000", "서울특별시", "서울특별시")
        val gangnam = Region.child("1168000000", 2, seoul, "서울특별시 강남구", "강남구")
        return Region.child("1168010100", 3, gangnam, "서울특별시 강남구 역삼동", "역삼동")
    }

    @Nested
    @DisplayName("판매내역 조회")
    inner class GetSales {
        @Test
        fun `거래완료된 내 상품만 판매내역에 포함되고 지역 정보도 함께 담긴다`() {
            val onSale = product(1L, "판매중 상품", BigDecimal.valueOf(10000), null)
            val completed = completedProduct(2L, "완료 상품", BigDecimal.valueOf(20000), daysAgo(1))
            given(productRepository.findAllByMemberIdAndDeletedAtIsNullOrderByIdDesc(ME_ID)).willReturn(listOf(onSale, completed))

            val sales = tradeService.getSales(ME_ID)

            assertThat(sales.map { it.productId }).containsExactly(2L)
            assertThat(sales[0].regionCode).isEqualTo("1168010100")
            assertThat(sales[0].regionName).isEqualTo("역삼동")
            assertThat(sales[0].regionFullName).isEqualTo("서울특별시 강남구 역삼동")
        }

        @Test
        fun `판매내역은 최근 완료순으로 정렬된다`() {
            val older = completedProduct(1L, "먼저 완료", BigDecimal.valueOf(10000), daysAgo(5))
            val newer = completedProduct(2L, "나중 완료", BigDecimal.valueOf(20000), daysAgo(1))
            given(productRepository.findAllByMemberIdAndDeletedAtIsNullOrderByIdDesc(ME_ID)).willReturn(listOf(older, newer))

            val sales = tradeService.getSales(ME_ID)

            assertThat(sales.map { it.productId }).containsExactly(2L, 1L)
        }
    }

    @Nested
    @DisplayName("구매내역 조회")
    inner class GetPurchases {
        @Test
        fun `내가 구매자이면서 상품이 거래완료된 방만 구매내역에 포함된다`() {
            val completed = completedProduct(1L, "완료 상품", BigDecimal.valueOf(10000), daysAgo(1))
            val notCompleted = product(2L, "미완료 상품", BigDecimal.valueOf(20000), null)
            val asBuyerCompleted = chatRoom(1L, completed, ME_ID, OTHER_ID)
            val asBuyerNotCompleted = chatRoom(2L, notCompleted, ME_ID, OTHER_ID)
            val asSeller = chatRoom(3L, completedProduct(3L, "내가 판 상품", BigDecimal.valueOf(30000), daysAgo(1)), OTHER_ID, ME_ID)
            given(chatRoomRepository.findMyChatRooms(ME_ID))
                .willReturn(listOf(asBuyerCompleted, asBuyerNotCompleted, asSeller))

            val purchases = tradeService.getPurchases(ME_ID)

            assertThat(purchases.map { it.productId }).containsExactly(1L)
        }

        @Test
        fun `구매내역은 최근 완료순으로 정렬된다`() {
            val older = completedProduct(1L, "먼저 완료", BigDecimal.valueOf(10000), daysAgo(5))
            val newer = completedProduct(2L, "나중 완료", BigDecimal.valueOf(20000), daysAgo(1))
            val olderRoom = chatRoom(1L, older, ME_ID, OTHER_ID)
            val newerRoom = chatRoom(2L, newer, ME_ID, OTHER_ID)
            given(chatRoomRepository.findMyChatRooms(ME_ID)).willReturn(listOf(olderRoom, newerRoom))

            val purchases = tradeService.getPurchases(ME_ID)

            assertThat(purchases.map { it.productId }).containsExactly(2L, 1L)
        }
    }

    @Nested
    @DisplayName("월별 거래 통계")
    inner class GetMonthlyStats {
        @Test
        fun `판매와 구매를 월별로 건수 금액을 집계한다`() {
            val saleJune = completedProduct(1L, "6월 판매", BigDecimal.valueOf(10000), atMonth(2026, 6))
            val saleJuly = completedProduct(2L, "7월 판매", BigDecimal.valueOf(20000), atMonth(2026, 7))
            val purchaseProduct = completedProduct(3L, "7월 구매", BigDecimal.valueOf(5000), atMonth(2026, 7))
            given(productRepository.findAllByMemberIdAndDeletedAtIsNullOrderByIdDesc(ME_ID)).willReturn(listOf(saleJune, saleJuly))
            given(chatRoomRepository.findMyChatRooms(ME_ID)).willReturn(listOf(chatRoom(1L, purchaseProduct, ME_ID, OTHER_ID)))

            val stats = tradeService.getMonthlyStats(ME_ID)

            assertThat(stats).hasSize(2)
            val july = stats[0]
            assertThat(july.yearMonth).isEqualTo("2026-07")
            assertThat(july.salesCount).isEqualTo(1)
            assertThat(july.salesAmount).isEqualByComparingTo("20000")
            assertThat(july.purchasesCount).isEqualTo(1)
            assertThat(july.purchasesAmount).isEqualByComparingTo("5000")

            val june = stats[1]
            assertThat(june.yearMonth).isEqualTo("2026-06")
            assertThat(june.salesCount).isEqualTo(1)
            assertThat(june.salesAmount).isEqualByComparingTo("10000")
            assertThat(june.purchasesCount).isZero()
            assertThat(june.purchasesAmount).isEqualByComparingTo("0")
        }

        @Test
        fun `최신 달이 먼저 온다`() {
            given(productRepository.findAllByMemberIdAndDeletedAtIsNullOrderByIdDesc(ME_ID)).willReturn(
                listOf(
                    completedProduct(1L, "1월", BigDecimal.valueOf(1000), atMonth(2026, 1)),
                    completedProduct(2L, "3월", BigDecimal.valueOf(1000), atMonth(2026, 3)),
                    completedProduct(3L, "2월", BigDecimal.valueOf(1000), atMonth(2026, 2)),
                ),
            )
            given(chatRoomRepository.findMyChatRooms(ME_ID)).willReturn(emptyList())

            val stats = tradeService.getMonthlyStats(ME_ID)

            assertThat(stats.map { it.yearMonth }).containsExactly("2026-03", "2026-02", "2026-01")
        }
    }

    companion object {
        private const val ME_ID = 1L
        private const val OTHER_ID = 2L
    }
}
