package com.dongnemarket.auction.controller

import com.dongnemarket.auction.entity.Auction
import com.dongnemarket.auction.repository.AuctionRepository
import com.dongnemarket.global.security.jwt.JwtTokenProvider
import org.junit.jupiter.api.AfterEach
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
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * [통합] 경매 REST — 등록(인증 필요)·조회. Auction 은 member FK 가 없어 토큰만으로 검증한다.
 * 시나리오: 로그인한 사용자가 경매를 등록하면 판매자로 기록되고, 인증 없이는 거부되며, 등록한 경매를 조회할 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuctionControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    lateinit var auctionRepository: AuctionRepository

    @AfterEach
    fun cleanUp() {
        auctionRepository.deleteAll()
    }

    private fun bearer(): String = "Bearer " + jwtTokenProvider.createAccessToken(1L, "ROLE_USER")

    @Test
    fun `POST 경매 등록 - 인증 사용자가 등록하면 201 · 판매자=본인 · ONGOING`() {
        val body = """{"title":"Integration Auction","startPrice":10000,"endAt":"2099-12-31T23:59:59"}"""

        mockMvc
            .perform(
                post("/api/auctions")
                    .header("Authorization", bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.sellerId").value(1))
            .andExpect(jsonPath("$.data.status").value("ONGOING"))
            .andExpect(jsonPath("$.data.currentPrice").value(10000))
    }

    @Test
    fun `POST 경매 등록 - 인증 없이 등록하면 401`() {
        val body = """{"title":"x","startPrice":100,"endAt":"2099-12-31T23:59:59"}"""

        mockMvc
            .perform(
                post("/api/auctions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isUnauthorized())
    }

    @Test
    fun `GET 경매 상세 - 등록된 경매를 조회하면 200 · 현재가`() {
        val saved =
            auctionRepository.save(
                Auction.create(
                    1L,
                    "Detail Auction",
                    null,
                    null,
                    BigDecimal.valueOf(5000L),
                    LocalDateTime.now().plusHours(1),
                ),
            )

        mockMvc
            .perform(
                get("/api/auctions/" + saved.id).header("Authorization", bearer()),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.auctionId").value(saved.id))
            .andExpect(jsonPath("$.data.currentPrice").value(5000))
    }
}
