package com.dongnemarket.product.controller

import com.dongnemarket.global.security.jwt.JwtTokenProvider
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Path

/**
 * [통합] 상품 이미지 REST — 업로드(인증 필요)와 업로드된 URL 로의 조회.
 * 저장 경로는 @DynamicPropertySource 로 임시 디렉터리를 주입해 실제 uploads/ 를 건드리지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductImageControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    lateinit var memberRepository: MemberRepository

    @AfterEach
    fun cleanUp() {
        memberRepository.deleteAll()
    }

    @Test
    fun `인증된 사용자는 상품 이미지 1장을 업로드할 수 있다`() {
        val token = accessToken()
        val file = imageFile("product.jpg", "image/jpeg", "product image")

        mockMvc
            .perform(
                multipart("/api/products/images")
                    .file(file)
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value(201))
            .andExpect(jsonPath("$.message").value("상품 이미지가 업로드되었습니다."))
            .andExpect(jsonPath("$.data.imageUrls.length()").value(1))
            .andExpect(jsonPath("$.data.imageUrls[0]").value(startsWith(IMAGE_URL_PREFIX)))
    }

    @Test
    fun `인증된 사용자는 상품 이미지 5장을 업로드할 수 있다`() {
        val token = accessToken()

        mockMvc
            .perform(
                multipart("/api/products/images")
                    .file(imageFile("1.jpg", "image/jpeg", "1"))
                    .file(imageFile("2.jpg", "image/jpeg", "2"))
                    .file(imageFile("3.jpg", "image/jpeg", "3"))
                    .file(imageFile("4.jpg", "image/jpeg", "4"))
                    .file(imageFile("5.jpg", "image/jpeg", "5"))
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.imageUrls.length()").value(5))
    }

    @Test
    fun `상품 이미지를 6장 업로드하면 INVALID_INPUT_VALUE를 반환한다`() {
        val token = accessToken()

        mockMvc
            .perform(
                multipart("/api/products/images")
                    .file(imageFile("1.jpg", "image/jpeg", "1"))
                    .file(imageFile("2.jpg", "image/jpeg", "2"))
                    .file(imageFile("3.jpg", "image/jpeg", "3"))
                    .file(imageFile("4.jpg", "image/jpeg", "4"))
                    .file(imageFile("5.jpg", "image/jpeg", "5"))
                    .file(imageFile("6.jpg", "image/jpeg", "6"))
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    @Test
    fun `이미지가 아닌 파일을 업로드하면 INVALID_INPUT_VALUE를 반환한다`() {
        val token = accessToken()
        val file = MockMultipartFile("files", "memo.txt", "text/plain", "memo".toByteArray())

        mockMvc
            .perform(
                multipart("/api/products/images")
                    .file(file)
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    @Test
    fun `인증 없이 상품 이미지를 업로드하면 401을 반환한다`() {
        val file = imageFile("product.jpg", "image/jpeg", "product image")

        mockMvc
            .perform(multipart("/api/products/images").file(file))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
    }

    @Test
    fun `업로드 응답 URL로 상품 이미지를 조회할 수 있다`() {
        val token = accessToken()
        val file = imageFile("product.png", "image/png", "product image")
        val uploadResult =
            mockMvc
                .perform(
                    multipart("/api/products/images")
                        .file(file)
                        .header("Authorization", "Bearer $token"),
                ).andExpect(status().isCreated())
                .andReturn()
        val responseBody = uploadResult.response.contentAsString
        val urlStart = responseBody.indexOf(IMAGE_URL_PREFIX)
        val imageUrl = responseBody.substring(urlStart, responseBody.indexOf("\"", urlStart))
        val filename = imageUrl.substring(IMAGE_URL_PREFIX.length)

        val imageResult =
            mockMvc
                .perform(
                    get("/api/products/images/{filename}", filename)
                        .header("Authorization", "Bearer $token"),
                ).andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andReturn()

        assertThat(imageResult.response.contentAsByteArray).isEqualTo("product image".toByteArray())
    }

    @Test
    fun `존재하지 않는 상품 이미지를 조회하면 PRODUCT_NOT_FOUND를 반환한다`() {
        val token = accessToken()

        mockMvc
            .perform(
                get("/api/products/images/{filename}", "missing.png")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"))
    }

    private fun accessToken(): String {
        val member = memberRepository.save(Member.createUser("product-image-uploader@example.com", "encodedPassword", "이미지업로더"))
        return jwtTokenProvider.createAccessToken(member.id, member.role.name)
    }

    private fun imageFile(
        filename: String,
        contentType: String,
        content: String,
    ): MockMultipartFile = MockMultipartFile("files", filename, contentType, content.toByteArray())

    companion object {
        private const val IMAGE_URL_PREFIX = "/api/products/images/"

        @field:TempDir
        @JvmStatic
        lateinit var productImageTempDir: Path

        @JvmStatic
        @DynamicPropertySource
        fun productImageProperties(registry: DynamicPropertyRegistry) {
            registry.add("file.storage.local.base-path") { productImageTempDir.toString() }
        }
    }
}
