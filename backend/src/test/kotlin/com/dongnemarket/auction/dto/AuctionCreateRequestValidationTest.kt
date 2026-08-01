package com.dongnemarket.auction.dto

import com.dongnemarket.global.security.jwt.JwtTokenProvider
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

/**
 * [통합] Kotlin 주 생성자 프로퍼티에 붙인 검증 어노테이션이 **실제로 동작하는지** 확인한다.
 *
 * Kotlin 은 주 생성자 프로퍼티의 어노테이션을 param → property → field 순으로 배치하는데,
 * `@NotBlank` 는 PARAMETER 를 허용하므로 use-site 를 생략하면 **파라미터에 붙는다.**
 * 그 상태에서 Bean Validation 이 걸리는지는 프로젝트마다 확인이 필요하다
 * (팀 규칙은 `@field:` 지만 근거를 실측한 적이 없었다 — HANDOFF §5).
 *
 * 이 테스트가 깨지면 `AuctionCreateRequest` 의 `@field:` 가 빠졌다는 뜻이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuctionCreateRequestValidationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    private fun bearer(): String = "Bearer " + jwtTokenProvider.createAccessToken(1L, "ROLE_USER")

    @Test
    fun `제목이 공백만 있으면 400과 NotBlank 메시지를 반환한다`() {
        val body = """{"title":"   ","startPrice":10000,"endAt":"2099-12-31T23:59:59"}"""

        mockMvc
            .perform(
                post("/api/auctions")
                    .header("Authorization", bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("제목은 필수입니다."))
    }
}
