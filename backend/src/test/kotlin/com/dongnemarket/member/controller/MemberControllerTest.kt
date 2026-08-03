package com.dongnemarket.member.controller

import com.dongnemarket.auth.entity.EmailVerification
import com.dongnemarket.auth.repository.EmailVerificationRepository
import com.dongnemarket.member.repository.MemberAgreementRepository
import com.dongnemarket.member.repository.MemberRepository
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

/**
 * 실제 HTTP 요청으로 내 정보 조회/수정 성공/실패를 검증하는 통합 테스트 (H2, MySQL/Docker 불필요).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var memberAgreementRepository: MemberAgreementRepository

    @Autowired
    lateinit var emailVerificationRepository: EmailVerificationRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @AfterEach
    fun cleanUp() {
        memberAgreementRepository.deleteAll()
        memberRepository.deleteAll()
        emailVerificationRepository.deleteAll()
    }

    /** 회원가입은 이메일 인증 완료를 전제로 하므로, signup을 호출하기 전에 인증 완료 상태를 만들어둔다. */
    private fun verifyEmail(email: String) {
        emailVerificationRepository.save(EmailVerification.verified(email, LocalDateTime.now()))
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

    @Test
    fun `유효한 토큰으로 GET 내 정보를 요청하면 200과 내 정보를 반환한다`() {
        val token = getAccessToken("me@example.com", "password123!", "meUser")

        mockMvc
            .perform(
                get("/api/members/me")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.email").value("me@example.com"))
            .andExpect(jsonPath("$.data.nickname").value("meUser"))
            .andExpect(jsonPath("$.data.memberId").exists())
            .andExpect(jsonPath("$.data.role").value("ROLE_USER"))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
    }

    @Test
    fun `토큰 없이 GET 내 정보를 요청하면 401을 반환한다`() {
        mockMvc
            .perform(get("/api/members/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
    }

    @Test
    fun `유효하지 않은 토큰으로 GET 내 정보를 요청하면 401과 INVALID_TOKEN을 반환한다`() {
        mockMvc
            .perform(
                get("/api/members/me")
                    .header("Authorization", "Bearer invalid.jwt.token"),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("INVALID_TOKEN"))
    }

    // ===== PATCH /api/members/me =====

    @Test
    fun `유효한 토큰으로 PATCH 내 정보를 요청하면 200과 변경된 닉네임을 반환한다`() {
        val token = getAccessToken("patch@example.com", "password123!", "oldNick")

        mockMvc
            .perform(
                patch("/api/members/me")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nickname\":\"newNick\"}"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.nickname").value("newNick"))
            .andExpect(jsonPath("$.data.email").value("patch@example.com"))
    }

    @Test
    fun `토큰 없이 PATCH 내 정보를 요청하면 401을 반환한다`() {
        mockMvc
            .perform(
                patch("/api/members/me")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nickname\":\"newNick\"}"),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
    }

    @Test
    fun `다른 회원이 사용 중인 닉네임으로 수정하면 409와 DUPLICATE_NICKNAME을 반환한다`() {
        verifyEmail("other@example.com")
        mockMvc.perform(
            post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"other@example.com\",\"password\":\"password123!\",\"nickname\":\"takenNick\"," +
                        "\"termsAgreed\":true,\"personalInfoCollectionAgreed\":true}",
                ),
        )

        val token = getAccessToken("me2@example.com", "password123!", "myNick")

        mockMvc
            .perform(
                patch("/api/members/me")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nickname\":\"takenNick\"}"),
            ).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("DUPLICATE_NICKNAME"))
    }

    @Test
    fun `닉네임이 1자이면 400과 INVALID_INPUT_VALUE를 반환한다`() {
        val token = getAccessToken("valid@example.com", "password123!", "validUser")

        mockMvc
            .perform(
                patch("/api/members/me")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nickname\":\"x\"}"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    // ===== PATCH /api/members/me/password =====

    @Test
    fun `현재 비밀번호가 맞고 새 비밀번호가 다르면 200을 반환한다`() {
        val token = getAccessToken("pwchange@example.com", "password123!", "pwChangeUser")

        mockMvc
            .perform(
                patch("/api/members/me/password")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currentPassword\":\"password123!\",\"newPassword\":\"newPassword123!\"}"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
    }

    @Test
    fun `변경된 비밀번호로 다시 로그인할 수 있다`() {
        val token = getAccessToken("pwchange-login@example.com", "password123!", "pwChangeLoginUser")
        mockMvc.perform(
            patch("/api/members/me/password")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"password123!\",\"newPassword\":\"newPassword123!\"}"),
        )

        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"pwchange-login@example.com\",\"password\":\"newPassword123!\"}"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").exists())
    }

    @Test
    fun `비밀번호 변경에 성공하면 기존 Refresh Token이 삭제되어 재발급이 REFRESH_TOKEN_NOT_FOUND로 실패한다`() {
        verifyEmail("pwchange-token@example.com")
        val signup =
            "{ \"email\": \"pwchange-token@example.com\", \"password\": \"password123!\", \"nickname\": \"pwChangeToken\", " +
                "\"termsAgreed\": true, \"personalInfoCollectionAgreed\": true }"
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup))

        val login = "{ \"email\": \"pwchange-token@example.com\", \"password\": \"password123!\" }"
        val loginResult =
            mockMvc
                .perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andReturn()
        val accessToken =
            objectMapper
                .readTree(loginResult.response.contentAsString)
                .path("data")
                .path("accessToken")
                .asText()
        val refreshTokenCookie = loginResult.response.getCookie("refreshToken")

        mockMvc.perform(
            patch("/api/members/me/password")
                .header("Authorization", "Bearer $accessToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"password123!\",\"newPassword\":\"newPassword123!\"}"),
        )

        mockMvc
            .perform(post("/api/auth/reissue").cookie(Cookie("refreshToken", refreshTokenCookie!!.value)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("REFRESH_TOKEN_NOT_FOUND"))
    }

    @Test
    fun `현재 비밀번호가 틀리면 401과 INVALID_PASSWORD를 반환한다`() {
        val token = getAccessToken("pwchange-wrong@example.com", "password123!", "pwChangeWrong")

        mockMvc
            .perform(
                patch("/api/members/me/password")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currentPassword\":\"wrongPassword123!\",\"newPassword\":\"newPassword123!\"}"),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("INVALID_PASSWORD"))
    }

    @Test
    fun `새 비밀번호가 현재 비밀번호와 같으면 400과 SAME_AS_OLD_PASSWORD를 반환한다`() {
        val token = getAccessToken("pwchange-same@example.com", "password123!", "pwChangeSame")

        mockMvc
            .perform(
                patch("/api/members/me/password")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currentPassword\":\"password123!\",\"newPassword\":\"password123!\"}"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("SAME_AS_OLD_PASSWORD"))
    }

    @Test
    fun `새 비밀번호가 정책에 맞지 않으면 400과 INVALID_INPUT_VALUE를 반환한다`() {
        val token = getAccessToken("pwchange-format@example.com", "password123!", "pwChangeFormat")

        mockMvc
            .perform(
                patch("/api/members/me/password")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currentPassword\":\"password123!\",\"newPassword\":\"nospecialchar123\"}"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    @Test
    fun `토큰 없이 PATCH 비밀번호 변경을 요청하면 401을 반환한다`() {
        mockMvc
            .perform(
                patch("/api/members/me/password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currentPassword\":\"password123!\",\"newPassword\":\"newPassword123!\"}"),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
    }

    @Test
    fun `탈퇴한 회원 토큰으로 비밀번호를 변경하면 400과 DELETED_MEMBER를 반환한다`() {
        val token = getAccessToken("pwchange-deleted@example.com", "password123!", "pwChangeDeleted")
        jdbcTemplate.update("UPDATE members SET status = 'DELETED' WHERE email = ?", "pwchange-deleted@example.com")

        mockMvc
            .perform(
                patch("/api/members/me/password")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currentPassword\":\"password123!\",\"newPassword\":\"newPassword123!\"}"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("DELETED_MEMBER"))
    }

    @Test
    fun `정지된 회원 토큰으로 비밀번호를 변경하면 403과 SUSPENDED_MEMBER를 반환한다`() {
        val token = getAccessToken("pwchange-suspended@example.com", "password123!", "pwChangeSuspended")
        jdbcTemplate.update("UPDATE members SET status = 'SUSPENDED' WHERE email = ?", "pwchange-suspended@example.com")

        mockMvc
            .perform(
                patch("/api/members/me/password")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currentPassword\":\"password123!\",\"newPassword\":\"newPassword123!\"}"),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("SUSPENDED_MEMBER"))
    }

    // ===== DELETE /api/members/me =====

    @Test
    fun `유효한 토큰으로 DELETE 회원 탈퇴를 요청하면 200을 반환한다`() {
        val token = getAccessToken("delete@example.com", "password123!", "deleteUser")

        mockMvc
            .perform(
                delete("/api/members/me")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
    }

    @Test
    fun `탈퇴 후 같은 계정으로 로그인하면 400과 DELETED_MEMBER를 반환한다`() {
        val token = getAccessToken("del2@example.com", "password123!", "del2User")
        mockMvc.perform(
            delete("/api/members/me")
                .header("Authorization", "Bearer $token"),
        )

        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"del2@example.com\",\"password\":\"password123\"}"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("DELETED_MEMBER"))
    }

    @Test
    fun `토큰 없이 DELETE 회원 탈퇴를 요청하면 401을 반환한다`() {
        mockMvc
            .perform(delete("/api/members/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
    }

    @Test
    fun `이미 탈퇴한 회원이 DELETE 회원 탈퇴를 재요청하면 400과 DELETED_MEMBER를 반환한다`() {
        val token = getAccessToken("del3@example.com", "password123!", "del3User")
        mockMvc.perform(
            delete("/api/members/me")
                .header("Authorization", "Bearer $token"),
        )

        mockMvc
            .perform(
                delete("/api/members/me")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("DELETED_MEMBER"))
    }

    @Test
    fun `정지된 회원이 DELETE 회원 탈퇴를 요청하면 403과 SUSPENDED_MEMBER를 반환한다`() {
        val token = getAccessToken("susp3@example.com", "password123!", "susp3User")
        jdbcTemplate.update("UPDATE members SET status = 'SUSPENDED' WHERE email = ?", "susp3@example.com")

        mockMvc
            .perform(
                delete("/api/members/me")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("SUSPENDED_MEMBER"))
    }

    // ===== 상태 체크 — GET =====

    @Test
    fun `탈퇴한 회원 토큰으로 GET 내 정보를 요청하면 400과 DELETED_MEMBER를 반환한다`() {
        val token = getAccessToken("del4@example.com", "password123!", "del4User")
        jdbcTemplate.update("UPDATE members SET status = 'DELETED' WHERE email = ?", "del4@example.com")

        mockMvc
            .perform(
                get("/api/members/me")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("DELETED_MEMBER"))
    }

    @Test
    fun `정지된 회원 토큰으로 GET 내 정보를 요청하면 403과 SUSPENDED_MEMBER를 반환한다`() {
        val token = getAccessToken("susp1@example.com", "password123!", "susp1User")
        jdbcTemplate.update("UPDATE members SET status = 'SUSPENDED' WHERE email = ?", "susp1@example.com")

        mockMvc
            .perform(
                get("/api/members/me")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("SUSPENDED_MEMBER"))
    }

    // ===== 상태 체크 — PATCH =====

    @Test
    fun `탈퇴한 회원 토큰으로 PATCH 내 정보를 요청하면 400과 DELETED_MEMBER를 반환한다`() {
        val token = getAccessToken("del5@example.com", "password123!", "del5User")
        jdbcTemplate.update("UPDATE members SET status = 'DELETED' WHERE email = ?", "del5@example.com")

        mockMvc
            .perform(
                patch("/api/members/me")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nickname\":\"newNick\"}"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("DELETED_MEMBER"))
    }

    @Test
    fun `정지된 회원 토큰으로 PATCH 내 정보를 요청하면 403과 SUSPENDED_MEMBER를 반환한다`() {
        val token = getAccessToken("susp2@example.com", "password123!", "susp2User")
        jdbcTemplate.update("UPDATE members SET status = 'SUSPENDED' WHERE email = ?", "susp2@example.com")

        mockMvc
            .perform(
                patch("/api/members/me")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nickname\":\"newNick\"}"),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("SUSPENDED_MEMBER"))
    }
}
