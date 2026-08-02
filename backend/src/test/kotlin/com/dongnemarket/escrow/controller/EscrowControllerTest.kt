package com.dongnemarket.escrow.controller

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.escrow.entity.Escrow
import com.dongnemarket.escrow.repository.EscrowRepository
import com.dongnemarket.global.security.jwt.JwtTokenProvider
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.TradeStatus
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EscrowControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var categoryRepository: CategoryRepository

    @Autowired
    lateinit var productRepository: ProductRepository

    @Autowired
    lateinit var escrowRepository: EscrowRepository

    @Autowired
    lateinit var regionRepository: RegionRepository

    @AfterEach
    fun cleanUp() {
        escrowRepository.deleteAll()
        productRepository.deleteAll()
        categoryRepository.deleteAll()
        memberRepository.deleteAll()
    }

    // ===== 픽스처 =====

    private fun saveMember(
        email: String,
        nickname: String,
    ): Member = memberRepository.save(Member.createUser(email, "encodedPassword", nickname))

    private fun saveOnSaleProduct(seller: Member): Product {
        val category = categoryRepository.save(Category("생활/가전"))
        return productRepository.save(
            Product.create(seller, category, "남은 고기", "같이 먹다 남은 고기", PRICE, findRegion("5115010100")),
        )
    }

    private fun findRegion(code: String): Region = regionRepository.findByCode(code).orElseThrow()

    /** 예치중(IN_ESCROW) 거래 + 상품 RESERVED 상태를 준비한다(실제 create가 만드는 상태와 동일). */
    private fun saveEscrow(
        product: Product,
        buyer: Member,
        seller: Member,
    ): Escrow {
        product.changeTradeStatus(TradeStatus.RESERVED)
        productRepository.save(product)
        return escrowRepository.save(Escrow.create(product, buyer, seller, product.price))
    }

    private fun token(member: Member): String = "Bearer " + jwtTokenProvider.createAccessToken(member.id, member.role.name)

    // ===== E1: 거래 시작 =====

    @Test
    fun `구매자가 판매중 상품에 안심결제를 시작하면 201·IN_ESCROW로 예치되고 상품이 RESERVED가 된다`() {
        // given
        val seller = saveMember("seller@example.com", "판매자")
        val buyer = saveMember("buyer@example.com", "구매자")
        val product = saveOnSaleProduct(seller)

        // when
        mockMvc
            .perform(
                post("/api/escrows")
                    .header("Authorization", token(buyer))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"productId": ${product.id}}"""),
            )
            // then
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value(201))
            .andExpect(jsonPath("$.data.escrowId").exists())
            .andExpect(jsonPath("$.data.status").value("IN_ESCROW"))
            .andExpect(jsonPath("$.data.amount").value(15000))
            .andExpect(jsonPath("$.data.buyerId").value(buyer.id))
            .andExpect(jsonPath("$.data.sellerId").value(seller.id))

        val reserved = productRepository.findById(product.id!!).orElseThrow()
        assertThat(reserved.tradeStatus).isEqualTo(TradeStatus.RESERVED)
    }

    // ===== E2: 구매확정 =====

    @Test
    fun `구매자가 구매확정하면 200·DONE으로 정산되고 상품이 COMPLETED가 된다`() {
        // given
        val seller = saveMember("seller@example.com", "판매자")
        val buyer = saveMember("buyer@example.com", "구매자")
        val product = saveOnSaleProduct(seller)
        val escrow = saveEscrow(product, buyer, seller)

        // when
        mockMvc
            .perform(
                post("/api/escrows/{id}/confirm", escrow.id)
                    .header("Authorization", token(buyer)),
            )
            // then
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("DONE"))

        val completed = productRepository.findById(product.id!!).orElseThrow()
        assertThat(completed.tradeStatus).isEqualTo(TradeStatus.COMPLETED)
    }

    // ===== E3: 취소 =====

    @Test
    fun `구매자가 취소하면 200·CANCELED로 환불되고 상품이 다시 ON_SALE로 돌아온다`() {
        // given
        val seller = saveMember("seller@example.com", "판매자")
        val buyer = saveMember("buyer@example.com", "구매자")
        val product = saveOnSaleProduct(seller)
        val escrow = saveEscrow(product, buyer, seller)

        // when
        mockMvc
            .perform(
                post("/api/escrows/{id}/cancel", escrow.id)
                    .header("Authorization", token(buyer)),
            )
            // then
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CANCELED"))

        val onSale = productRepository.findById(product.id!!).orElseThrow()
        assertThat(onSale.tradeStatus).isEqualTo(TradeStatus.ON_SALE)
    }

    // ===== E4: 본인 상품 =====

    @Test
    fun `판매자가 본인 상품에 거래를 시작하면 400 CANNOT_ESCROW_OWN_PRODUCT`() {
        val seller = saveMember("seller@example.com", "판매자")
        val product = saveOnSaleProduct(seller)

        mockMvc
            .perform(
                post("/api/escrows")
                    .header("Authorization", token(seller))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"productId": ${product.id}}"""),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("CANNOT_ESCROW_OWN_PRODUCT"))
    }

    // ===== E5: 거래중(RESERVED) 상품 재시작 =====

    @Test
    fun `이미 예치중이라 RESERVED가 된 상품에 다른 구매자가 거래를 시작하면 400 PRODUCT_NOT_ON_SALE`() {
        val seller = saveMember("seller@example.com", "판매자")
        val buyerA = saveMember("buyerA@example.com", "구매자A")
        val buyerB = saveMember("buyerB@example.com", "구매자B")
        val product = saveOnSaleProduct(seller)
        saveEscrow(product, buyerA, seller) // 이미 진행 중 → 상품 RESERVED

        mockMvc
            .perform(
                post("/api/escrows")
                    .header("Authorization", token(buyerB))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"productId": ${product.id}}"""),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("PRODUCT_NOT_ON_SALE"))
    }

    // ===== E6: 남의 거래 =====

    @Test
    fun `자신의 거래가 아닌 거래를 확정하려 하면 403 ESCROW_ACCESS_DENIED`() {
        val seller = saveMember("seller@example.com", "판매자")
        val buyer = saveMember("buyer@example.com", "구매자")
        val stranger = saveMember("stranger@example.com", "제3자")
        val escrow = saveEscrow(saveOnSaleProduct(seller), buyer, seller)

        mockMvc
            .perform(
                post("/api/escrows/{id}/confirm", escrow.id)
                    .header("Authorization", token(stranger)),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("ESCROW_ACCESS_DENIED"))
    }

    // ===== E7: 인증 없음 =====

    @Test
    fun `인증 없이 거래를 시작하면 401을 반환한다`() {
        mockMvc
            .perform(
                post("/api/escrows")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"productId": 1}"""),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
    }

    // ===== E8: 종료된 거래 재확정 =====

    @Test
    fun `이미 종료(DONE)된 거래를 다시 확정하면 409 ESCROW_NOT_IN_ESCROW`() {
        val seller = saveMember("seller@example.com", "판매자")
        val buyer = saveMember("buyer@example.com", "구매자")
        val escrow = saveEscrow(saveOnSaleProduct(seller), buyer, seller)
        escrow.confirm() // DONE으로 만든 뒤 저장
        escrowRepository.save(escrow)

        mockMvc
            .perform(
                post("/api/escrows/{id}/confirm", escrow.id)
                    .header("Authorization", token(buyer)),
            ).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("ESCROW_NOT_IN_ESCROW"))
    }

    companion object {
        /** BigDecimal.valueOf 는 함수 호출이라 컴파일 타임 상수가 아니다 → const val 을 못 쓴다. */
        private val PRICE: BigDecimal = BigDecimal.valueOf(15_000L)
    }
}
