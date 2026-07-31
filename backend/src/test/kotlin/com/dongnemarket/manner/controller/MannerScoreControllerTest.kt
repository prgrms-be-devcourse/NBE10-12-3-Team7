package com.dongnemarket.manner.controller

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.chat.entity.ChatRoom
import com.dongnemarket.chat.repository.ChatRoomRepository
import com.dongnemarket.global.security.jwt.JwtTokenProvider
import com.dongnemarket.manner.dto.MannerRatingCreateRequest
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.TradeStatus
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/** [E2E] 매너온도 조회 API. 서비스·레포지토리를 목킹하지 않고 실제 HTTP → 실제 DB까지 태운다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MannerScoreControllerTest {
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

    private fun token(memberId: Long): String = "Bearer " + jwtTokenProvider.createAccessToken(memberId, "ROLE_USER")

    @Test
    @DisplayName("회원 매너온도 조회는 토큰 없이도 200과 기본값(36.5)을 받는다(permitAll, 레코드 자동 생성)")
    fun getScore_noToken_returnsDefault() {
        val member = memberRepository.save(Member.createUser("manner-e2e-score@example.com", "pw", "회원"))

        mockMvc
            .perform(get("/api/members/${member.id}/manner-score"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.memberId").value(member.id))
            .andExpect(jsonPath("$.data.score").value(36.5))
    }

    @Test
    @DisplayName("내 매너온도 변화 이력 조회는 토큰 없이 요청하면 401을 받는다")
    fun getMyHistory_noToken_returns401() {
        mockMvc
            .perform(get("/api/members/me/manner-score/history"))
            .andExpect(status().isUnauthorized())
    }

    @Test
    @DisplayName("판매자가 별점을 받으면, 그 판매자의 이력 조회에 RATING_RECEIVED 항목이 실제로 나타난다")
    fun ratingFlow_reflectedInSellerHistory() {
        // given: 완료된 거래 + 채팅방(별점 등록의 전제 조건)을 실제로 만든다.
        val seller = memberRepository.save(Member.createUser("manner-e2e-history-seller@example.com", "pw", "판매자"))
        val buyer = memberRepository.save(Member.createUser("manner-e2e-history-buyer@example.com", "pw", "구매자"))
        val category = categoryRepository.save(Category("매너이력E2E테스트카테고리"))
        val region =
            regionRepository
                .findByCode("1100000000")
                .orElseGet { regionRepository.save(Region.root("1100000000", "서울특별시", "서울특별시")) }
        val product =
            productRepository.save(Product.create(seller, category, "완료된 거래 상품", "설명", BigDecimal.valueOf(10000), region))
        product.changeTradeStatus(TradeStatus.COMPLETED)
        chatRoomRepository.save(ChatRoom.of(product, buyer, seller))

        // when: 구매자가 판매자에게 별점 5점을 등록한다(실제 HTTP POST).
        val body = objectMapper.writeValueAsString(MannerRatingCreateRequest(product.id, 5))
        mockMvc
            .perform(
                post("/api/manner/ratings")
                    .header("Authorization", token(buyer.id!!))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isCreated())

        // then: 판매자 본인이 자신의 이력을 조회하면 방금 반영된 RATING_RECEIVED 이력이 보인다.
        mockMvc
            .perform(get("/api/members/me/manner-score/history").header("Authorization", token(seller.id!!)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].reason").value("RATING_RECEIVED"))
            .andExpect(jsonPath("$.data[0].changeAmount").value(0.2))
    }
}
