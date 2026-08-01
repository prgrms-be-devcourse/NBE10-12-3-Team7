package com.dongnemarket.admin.controller

import com.dongnemarket.admin.dto.OrphanDeleteRequest
import com.dongnemarket.admin.dto.OrphanDeleteRequest.OrphanTarget
import com.dongnemarket.global.security.jwt.JwtTokenProvider
import com.dongnemarket.global.storage.FileStorageService
import com.dongnemarket.global.storage.StoredObject
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant

/**
 * [통합/E2E] 관리자 저장소 고아파일 API. 보안(ADMIN)·응답 봉투를 엔드포인트로 검증한다.
 * FileStorageService만 목으로 대체(디스크/S3 무관하게 결정적으로), 참조 레포는 실제 H2(빈 상태)를 탄다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminStorageControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockitoBean
    lateinit var fileStorageService: FileStorageService

    private fun adminToken(): String = "Bearer " + jwtTokenProvider.createAccessToken(1L, "ROLE_ADMIN")

    private fun userToken(): String = "Bearer " + jwtTokenProvider.createAccessToken(2L, "ROLE_USER")

    private fun oldOrphan(name: String): StoredObject = StoredObject(name, 123L, Instant.now().minus(Duration.ofDays(2)))

    @Test
    fun `관리자가 고아 목록을 조회하면 200과 봉투(orphans, totalCount)를 받는다`() {
        given(fileStorageService.list("product-images")).willReturn(listOf(oldOrphan("orphan.png")))
        given(fileStorageService.list("report-evidence")).willReturn(emptyList())

        mockMvc
            .perform(get("/api/admin/storage/orphans").header("Authorization", adminToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.totalCount").value(1))
            .andExpect(jsonPath("$.data.orphans[0].filename").value("orphan.png"))
            .andExpect(jsonPath("$.data.orphans[0].directory").value("product-images"))
    }

    @Test
    fun `토큰 없이 조회하면 401`() {
        mockMvc
            .perform(get("/api/admin/storage/orphans"))
            .andExpect(status().isUnauthorized())
    }

    @Test
    fun `일반 사용자(ROLE_USER)가 조회하면 403`() {
        mockMvc
            .perform(get("/api/admin/storage/orphans").header("Authorization", userToken()))
            .andExpect(status().isForbidden())
    }

    @Test
    fun `관리자가 고아를 삭제하면 200과 deleted 집계를 받는다`() {
        given(fileStorageService.list("product-images")).willReturn(listOf(oldOrphan("orphan.png")))
        given(fileStorageService.list("report-evidence")).willReturn(emptyList())
        given(fileStorageService.delete("orphan.png", "product-images")).willReturn(true)

        val body =
            objectMapper.writeValueAsString(
                OrphanDeleteRequest(listOf(OrphanTarget("product-images", "orphan.png"))),
            )

        mockMvc
            .perform(
                delete("/api/admin/storage/orphans")
                    .header("Authorization", adminToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.requested").value(1))
            .andExpect(jsonPath("$.data.deleted").value(1))
            .andExpect(jsonPath("$.data.skipped").value(0))
    }
}
