package com.dongnemarket.auction.controller;

import com.dongnemarket.auction.entity.Auction;
import com.dongnemarket.auction.repository.AuctionRepository;
import com.dongnemarket.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [통합] 경매 REST — 등록(인증 필요)·조회. Auction 은 member FK 가 없어 토큰만으로 검증한다.
 * 시나리오: 로그인한 사용자가 경매를 등록하면 판매자로 기록되고, 인증 없이는 거부되며, 등록한 경매를 조회할 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuctionControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired AuctionRepository auctionRepository;

    @AfterEach
    void cleanUp() {
        auctionRepository.deleteAll();
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.createAccessToken(1L, "ROLE_USER");
    }

    @Test
    @DisplayName("POST /api/auctions: 인증 사용자가 등록하면 201 · 판매자=본인 · ONGOING")
    void create_authenticated_returns201() throws Exception {
        String body = "{\"title\":\"Integration Auction\",\"startPrice\":10000,\"endAt\":\"2099-12-31T23:59:59\"}";

        mockMvc.perform(post("/api/auctions")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sellerId").value(1))
                .andExpect(jsonPath("$.data.status").value("ONGOING"))
                .andExpect(jsonPath("$.data.currentPrice").value(10000));
    }

    @Test
    @DisplayName("POST /api/auctions: 인증 없이 등록하면 401")
    void create_withoutAuth_returns401() throws Exception {
        String body = "{\"title\":\"x\",\"startPrice\":100,\"endAt\":\"2099-12-31T23:59:59\"}";

        mockMvc.perform(post("/api/auctions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/auctions/{id}: 등록된 경매를 조회하면 200 · 현재가")
    void getAuction_returns200() throws Exception {
        Auction saved = auctionRepository.save(Auction.create(
                1L, "Detail Auction", null, null,
                BigDecimal.valueOf(5000), LocalDateTime.now().plusHours(1)));

        mockMvc.perform(get("/api/auctions/" + saved.getId())
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auctionId").value(saved.getId()))
                .andExpect(jsonPath("$.data.currentPrice").value(5000));
    }
}
