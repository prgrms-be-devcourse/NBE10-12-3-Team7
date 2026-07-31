package com.dongnemarket.manner.controller

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.chat.entity.ChatRoom
import com.dongnemarket.chat.repository.ChatRoomRepository
import com.dongnemarket.global.security.jwt.JwtTokenProvider
import com.dongnemarket.manner.dto.MannerRatingCreateRequest
import com.dongnemarket.manner.repository.MannerRatingRepository
import com.dongnemarket.manner.repository.MannerScoreRepository
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.TradeStatus
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
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
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * [E2E] 거래 후 별점 등록 API. 서비스·레포지토리를 목킹하지 않고 실제 HTTP → 실제 DB까지 태운다.
 * 클래스에 [Transactional]을 걸어 각 테스트가 만든 회원·상품·채팅방·매너온도가 종료 후 롤백되게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MannerRatingControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var productRepository: ProductRepository

    @Autowired
    lateinit var categoryRepository: CategoryRepository

    @Autowired
    lateinit var regionRepository: RegionRepository

    @Autowired
    lateinit var chatRoomRepository: ChatRoomRepository

    @Autowired
    lateinit var mannerScoreRepository: MannerScoreRepository

    @Autowired
    lateinit var mannerRatingRepository: MannerRatingRepository

    private fun token(memberId: Long): String = "Bearer " + jwtTokenProvider.createAccessToken(memberId, "ROLE_USER")

    private fun saveProduct(tradeStatus: TradeStatus): Triple<Product, Member, Member> {
        val seller = memberRepository.save(Member.createUser("manner-e2e-seller@example.com", "pw", "판매자"))
        val buyer = memberRepository.save(Member.createUser("manner-e2e-buyer@example.com", "pw", "구매자"))
        val category = categoryRepository.save(Category("매너평점E2E테스트카테고리"))
        val region =
            regionRepository
                .findByCode("1100000000")
                .orElseGet { regionRepository.save(Region.root("1100000000", "서울특별시", "서울특별시")) }
        val product =
            productRepository.save(Product.create(seller, category, "완료된 거래 상품", "설명", BigDecimal.valueOf(10000), region))
        product.changeTradeStatus(tradeStatus)
        return Triple(product, seller, buyer)
    }

    @Test
    @DisplayName("완료된 거래의 구매자가 별점을 등록하면 201을 받고, DB 저장·매너온도 반영까지 실제로 일어난다")
    fun rate_validRequest_persistsAndUpdatesScore() {
        val (product, seller, buyer) = saveProduct(TradeStatus.COMPLETED)
        chatRoomRepository.save(ChatRoom.of(product, buyer, seller))
        val body = objectMapper.writeValueAsString(MannerRatingCreateRequest(product.id, 5))

        mockMvc
            .perform(
                post("/api/manner/ratings")
                    .header("Authorization", token(buyer.id!!))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.score").value(5))
            .andExpect(jsonPath("$.data.rateeId").value(seller.id))

        val score = mannerScoreRepository.findByMember_Id(seller.id!!).orElseThrow()
        assertThat(score.score).isEqualByComparingTo("36.7") // 기본값 36.5 + 별점 5점 반영분 0.2

        val savedRating = mannerRatingRepository.findAll().single()
        assertThat(savedRating.rater.id).isEqualTo(buyer.id)
        assertThat(savedRating.ratee.id).isEqualTo(seller.id)
        assertThat(savedRating.score).isEqualTo(5)
    }

    @Test
    @DisplayName("거래가 완료되지 않았으면 400을 받는다")
    fun rate_tradeNotCompleted_returns400() {
        val (product, seller, buyer) = saveProduct(TradeStatus.ON_SALE)
        chatRoomRepository.save(ChatRoom.of(product, buyer, seller))
        val body = objectMapper.writeValueAsString(MannerRatingCreateRequest(product.id, 5))

        mockMvc
            .perform(
                post("/api/manner/ratings")
                    .header("Authorization", token(buyer.id!!))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("거래가 완료된 상품만 후기를 등록할 수 있습니다."))
    }

    @Test
    @DisplayName("그 거래의 채팅방 참여자가 아니면 403을 받는다")
    fun rate_notAParticipant_returns403() {
        val (product, _, _) = saveProduct(TradeStatus.COMPLETED)
        val stranger = memberRepository.save(Member.createUser("manner-e2e-stranger@example.com", "pw", "무관한사람"))
        val body = objectMapper.writeValueAsString(MannerRatingCreateRequest(product.id, 5))

        mockMvc
            .perform(
                post("/api/manner/ratings")
                    .header("Authorization", token(stranger.id!!))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isForbidden())
    }

    @Test
    @DisplayName("productId가 없으면 400을 받는다")
    fun rate_missingProductId_returns400() {
        val body = """{"score":5}"""

        mockMvc
            .perform(
                post("/api/manner/ratings")
                    .header("Authorization", token(1L))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("상품 id는 필수입니다."))
    }

    @Test
    @DisplayName("토큰 없이 요청하면 401을 받는다")
    fun rate_noToken_returns401() {
        val body = objectMapper.writeValueAsString(MannerRatingCreateRequest(1L, 5))

        mockMvc
            .perform(
                post("/api/manner/ratings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isUnauthorized())
    }
}
