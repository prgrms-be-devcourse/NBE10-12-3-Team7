package com.dongnemarket.member.controller

import com.dongnemarket.auth.entity.EmailVerification
import com.dongnemarket.auth.repository.EmailVerificationRepository
import com.dongnemarket.member.repository.MemberAgreementRepository
import com.dongnemarket.member.repository.MemberLocationRepository
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.region.repository.RegionRepository
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberLocationControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var emailVerificationRepository: EmailVerificationRepository

    @Autowired
    lateinit var memberLocationRepository: MemberLocationRepository

    @Autowired
    lateinit var regionRepository: RegionRepository

    @Autowired
    lateinit var memberAgreementRepository: MemberAgreementRepository

    @AfterEach
    fun cleanUp() {
        memberLocationRepository.deleteAll()
        memberAgreementRepository.deleteAll()
        memberRepository.deleteAll()
        emailVerificationRepository.deleteAll()
    }

    /** 회원가입은 이메일 인증 완료를 전제로 하므로, signup을 호출하기 전에 인증 완료 상태를 만들어둔다. */
    private fun verifyEmail(email: String) {
        emailVerificationRepository.save(EmailVerification.verified(email, LocalDateTime.now()))
    }

    @Test
    fun `유효한 토큰으로 동네 2개를 설정하면 200과 저장된 동네 목록을 반환한다`() {
        val token = getAccessToken("locations-put@example.com", "password123!", "locPutUser")

        mockMvc
            .perform(
                put("/api/members/me/locations")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"regionCodes\":[\"1168010100\",\"1144012400\"]}"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data[0].region").doesNotExist())
            .andExpect(jsonPath("$.data[0].regionCode").value("1168010100"))
            .andExpect(jsonPath("$.data[0].regionName").value("역삼동"))
            .andExpect(jsonPath("$.data[0].regionFullName").value("서울특별시 강남구 역삼동"))
            .andExpect(jsonPath("$.data[0].sortOrder").value(0))
            .andExpect(jsonPath("$.data[0].active").value(true))
            .andExpect(jsonPath("$.data[1].region").doesNotExist())
            .andExpect(jsonPath("$.data[1].regionCode").value("1144012400"))
            .andExpect(jsonPath("$.data[1].regionName").value("연남동"))
            .andExpect(jsonPath("$.data[1].regionFullName").value("서울특별시 마포구 연남동"))
            .andExpect(jsonPath("$.data[1].sortOrder").value(1))
            .andExpect(jsonPath("$.data[1].active").value(false))
    }

    @Test
    fun `설정 후 조회하면 정렬 순서대로 동네 목록을 반환한다`() {
        val token = getAccessToken("locations-get@example.com", "password123!", "locGetUser")
        mockMvc.perform(
            put("/api/members/me/locations")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"regionCodes\":[\"1168010100\",\"1144012400\"]}"),
        )

        mockMvc
            .perform(
                get("/api/members/me/locations")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data[0].region").doesNotExist())
            .andExpect(jsonPath("$.data[0].regionCode").value("1168010100"))
            .andExpect(jsonPath("$.data[0].regionFullName").value("서울특별시 강남구 역삼동"))
            .andExpect(jsonPath("$.data[0].active").value(true))
            .andExpect(jsonPath("$.data[1].region").doesNotExist())
            .andExpect(jsonPath("$.data[1].regionCode").value("1144012400"))
            .andExpect(jsonPath("$.data[1].regionFullName").value("서울특별시 마포구 연남동"))
            .andExpect(jsonPath("$.data[1].active").value(false))
    }

    @Test
    fun `설정한 동네가 없으면 빈 목록을 반환한다`() {
        val token = getAccessToken("locations-empty@example.com", "password123!", "locEmptyUser")

        mockMvc
            .perform(
                get("/api/members/me/locations")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data").isEmpty())
    }

    @Test
    fun `토큰 없이 동네 설정을 요청하면 401을 반환한다`() {
        mockMvc
            .perform(
                put("/api/members/me/locations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"regionCodes\":[\"1168010100\"]}"),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
    }

    @Test
    fun `토큰 없이 동네 조회를 요청하면 401을 반환한다`() {
        mockMvc
            .perform(get("/api/members/me/locations"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
    }

    @Test
    fun `regionCode 목록이 빈 리스트이면 400과 INVALID_INPUT_VALUE를 반환한다`() {
        val token = getAccessToken("locations-empty-list@example.com", "password123!", "locEmptyListUser")

        mockMvc
            .perform(
                put("/api/members/me/locations")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"regionCodes\":[]}"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    @Test
    fun `regionCode 목록이 null이면 400과 INVALID_INPUT_VALUE를 반환한다`() {
        val token = getAccessToken("locations-null@example.com", "password123!", "locNullUser")

        mockMvc
            .perform(
                put("/api/members/me/locations")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"regionCodes\":null}"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    @Test
    fun `동네가 3개이면 400과 INVALID_INPUT_VALUE를 반환한다`() {
        val token = getAccessToken("locations-three@example.com", "password123!", "locThreeUser")

        mockMvc
            .perform(
                put("/api/members/me/locations")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"regionCodes\":[\"1168010100\",\"1144012400\",\"1171010100\"]}"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    @Test
    fun `regionCode 원소가 공백이면 400과 INVALID_INPUT_VALUE를 반환한다`() {
        val token = getAccessToken("locations-blank@example.com", "password123!", "locBlankUser")

        mockMvc
            .perform(
                put("/api/members/me/locations")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"regionCodes\":[\" \"]}"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    @Test
    fun `리스트 안에 중복 regionCode가 있으면 400과 INVALID_INPUT_VALUE를 반환한다`() {
        val token = getAccessToken("locations-duplicate@example.com", "password123!", "locDuplicateUser")

        mockMvc
            .perform(
                put("/api/members/me/locations")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"regionCodes\":[\"1168010100\",\"1168010100\"]}"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    @Test
    fun `지역 마스터에 없는 regionCode이면 400과 INVALID_INPUT_VALUE를 반환한다`() {
        val token = getAccessToken("locations-unknown@example.com", "password123!", "locUnknownUser")

        mockMvc
            .perform(
                put("/api/members/me/locations")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"regionCodes\":[\"9999999999\"]}"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    @Test
    fun `level 3이 아닌 regionCode이면 400과 INVALID_INPUT_VALUE를 반환한다`() {
        val token = getAccessToken("locations-level2@example.com", "password123!", "locLevel2User")

        mockMvc
            .perform(
                put("/api/members/me/locations")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"regionCodes\":[\"1168000000\"]}"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    private fun getAccessToken(
        email: String,
        password: String,
        nickname: String,
    ): String {
        verifyEmail(email)
        val signup =
            "{\"email\":\"$email\",\"password\":\"$password\",\"nickname\":\"$nickname\",\"termsAgreed\":true,\"personalInfoCollectionAgreed\":true}"
        mockMvc.perform(
            post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signup),
        )

        val login = "{\"email\":\"$email\",\"password\":\"$password\"}"
        val result =
            mockMvc
                .perform(
                    post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login),
                ).andReturn()

        return objectMapper
            .readTree(result.response.contentAsString)
            .path("data")
            .path("accessToken")
            .asText()
    }
}
