package com.dongnemarket.trade.controller

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.chat.entity.ChatRoom
import com.dongnemarket.chat.repository.ChatRoomRepository
import com.dongnemarket.global.security.jwt.JwtTokenProvider
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * [E2E] 거래내역(판매/구매/월별 통계) 조회 API. 서비스·레포지토리를 목으로 대체하지 않고
 * 실제 HTTP 요청 → 실제 H2 DB까지 전 구간을 태운다.
 * 클래스에 [Transactional]을 걸어 각 테스트가 만든 데이터가 종료 후 롤백되게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TradeControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

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

    private fun token(memberId: Long): String = "Bearer " + jwtTokenProvider.createAccessToken(memberId, "ROLE_USER")

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

    private fun saveCompletedProduct(
        seller: Member,
        title: String,
        price: BigDecimal,
    ): Product {
        val category = categoryRepository.save(Category("거래E2E테스트카테고리-$title"))
        val product = productRepository.save(Product.create(seller, category, title, "설명", price, saveTestRegion()))
        product.complete()
        return product
    }

    @Test
    fun `토큰 없이 판매내역을 조회하면 401`() {
        mockMvc
            .perform(get("/api/members/me/trades/sales"))
            .andExpect(status().isUnauthorized())
    }

    @Test
    fun `내가 판매한 거래완료 상품을 실제 DB에서 조회해 200과 함께 반환한다`() {
        // given: 실제 판매자·거래완료 상품을 DB에 저장한다.
        val seller = memberRepository.save(Member.createUser("trade-e2e-seller@example.com", "pw", "판매자"))
        val product = saveCompletedProduct(seller, "거래완료 상품", BigDecimal.valueOf(12000))

        // when: 실제 HTTP로 판매내역 API를 호출한다(서비스·레포지토리 목킹 없음).
        mockMvc
            .perform(get("/api/members/me/trades/sales").header("Authorization", token(seller.id!!)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].productId").value(product.id))
            .andExpect(jsonPath("$.data[0].title").value("거래완료 상품"))
            .andExpect(jsonPath("$.data[0].regionFullName").value("서울특별시 강남구 역삼동"))
    }

    @Test
    fun `내가 구매자로 참여한 채팅방 중 거래완료 상품만 구매내역으로 실제 DB에서 조회한다`() {
        // given: 실제 판매자·구매자·거래완료 상품·채팅방을 DB에 저장한다.
        val seller = memberRepository.save(Member.createUser("trade-e2e-seller2@example.com", "pw", "판매자"))
        val buyer = memberRepository.save(Member.createUser("trade-e2e-buyer@example.com", "pw", "구매자"))
        val product = saveCompletedProduct(seller, "구매한 상품", BigDecimal.valueOf(8000))
        val room = chatRoomRepository.save(ChatRoom.of(product, buyer, seller))

        // when & then: 실제 HTTP 응답과 실제 DB 상태를 함께 확인한다.
        mockMvc
            .perform(get("/api/members/me/trades/purchases").header("Authorization", token(buyer.id!!)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].productId").value(product.id))
            .andExpect(jsonPath("$.data[0].roomId").value(room.id))
            .andExpect(jsonPath("$.data[0].sellerNickname").value(seller.displayNickname))
    }

    @Test
    fun `월별 거래 통계는 판매와 구매를 함께 집계해 실제 DB 기준으로 반환한다`() {
        // given: 이번 달 판매 1건과 구매 1건을 실제로 만든다.
        val seller = memberRepository.save(Member.createUser("trade-e2e-seller3@example.com", "pw", "판매자"))
        val buyer = memberRepository.save(Member.createUser("trade-e2e-buyer3@example.com", "pw", "구매자"))
        val sale = saveCompletedProduct(seller, "이번달 판매", BigDecimal.valueOf(9000))
        val purchasedProduct = saveCompletedProduct(seller, "이번달 구매", BigDecimal.valueOf(4000))
        chatRoomRepository.save(ChatRoom.of(purchasedProduct, buyer, seller))

        // when & then: 판매자 입장에서는 판매 2건(자기 상품 2개 모두), 구매자 입장에서는 구매 1건이 집계된다.
        mockMvc
            .perform(get("/api/members/me/trades/monthly-stats").header("Authorization", token(seller.id!!)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].salesCount").value(2))
            .andExpect(jsonPath("$.data[0].salesAmount").value(sale.price.add(purchasedProduct.price).toDouble()))

        mockMvc
            .perform(get("/api/members/me/trades/monthly-stats").header("Authorization", token(buyer.id!!)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].purchasesCount").value(1))
            .andExpect(jsonPath("$.data[0].purchasesAmount").value(purchasedProduct.price.toDouble()))
    }
}
