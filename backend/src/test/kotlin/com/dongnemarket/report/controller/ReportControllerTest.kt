package com.dongnemarket.report.controller

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.global.security.jwt.JwtTokenProvider
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import com.dongnemarket.report.dto.ProductReportCreateRequest
import com.dongnemarket.report.entity.ReportReason
import com.dongnemarket.report.entity.ReportStatus
import com.dongnemarket.report.repository.ReportRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * [E2E] 상품 신고 생성/취소 API. 가이드가 정의하는 E2E("API를 직접 Call하여 Return이 올바른지 테스트")에
 * 맞춰 서비스·레포지토리를 목으로 대체하지 않고 실제 HTTP 요청 → 실제 H2 DB까지 전 구간을 태운다.
 * 검증(Bean Validation) 경계 테스트만 예외적으로 얕게 둔다 — @Valid가 컨트롤러 진입 전에 막아
 * 어차피 서비스/DB까지 내려가지 않기 때문이다.
 *
 * 클래스에 [Transactional]을 걸어 각 테스트가 만든 회원·상품·신고가 테스트 종료 후 롤백되게 한다
 * (다른 @SpringBootTest 클래스가 부팅 시 커밋해 둔 시드 카테고리와 이름이 겹쳐 유니크 제약을 위반했던
 * ReportRepositoryTest 사고를 반복하지 않기 위해서다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReportControllerTest {
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
    lateinit var reportRepository: ReportRepository

    private fun token(memberId: Long): String = "Bearer " + jwtTokenProvider.createAccessToken(memberId, "ROLE_USER")

    /** 실제 시드 데이터(CategorySeeder/RegionSeeder)와 겹치지 않는 테스트 전용 픽스처를 만든다. */
    private fun saveProduct(): Product {
        val seller = memberRepository.save(Member.createUser("report-e2e-seller@example.com", "pw", "판매자"))
        val category = categoryRepository.save(Category("리포트E2E테스트카테고리"))
        val region =
            regionRepository
                .findByCode("1100000000")
                .orElseGet { regionRepository.save(Region.root("1100000000", "서울특별시", "서울특별시")) }
        return productRepository.save(Product.create(seller, category, "가품 의심 상품", "설명", BigDecimal.valueOf(5000), region))
    }

    @Test
    fun `reason이 없으면 400과 필수 메시지를 받는다`() {
        val body = """{"content":"신고합니다"}"""

        mockMvc
            .perform(
                post("/api/products/10/reports")
                    .header("Authorization", token(1L))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("신고 사유는 필수입니다."))
    }

    @Test
    fun `content가 500자를 초과하면 400과 길이 제한 메시지를 받는다`() {
        val body =
            objectMapper.writeValueAsString(
                ProductReportCreateRequest(ReportReason.FAKE_ITEM, "가".repeat(501), null),
            )

        mockMvc
            .perform(
                post("/api/products/10/reports")
                    .header("Authorization", token(1L))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("신고 내용은 500자 이하여야 합니다."))
    }

    @Test
    fun `토큰 없이 요청하면 401`() {
        val body =
            objectMapper.writeValueAsString(
                ProductReportCreateRequest(ReportReason.FAKE_ITEM, "가품 같아요", null),
            )

        mockMvc
            .perform(
                post("/api/products/10/reports")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isUnauthorized())
    }

    @Test
    fun `로그인 사용자가 상품을 신고하면 201을 받고 DB에 RECEIVED 상태로 실제 저장된다`() {
        // given: 실제 판매자·상품을 DB에 저장하고, 신고자로 로그인한다.
        val product = saveProduct()
        val reporter = memberRepository.save(Member.createUser("report-e2e-reporter@example.com", "pw", "신고자"))
        val body =
            objectMapper.writeValueAsString(
                ProductReportCreateRequest(ReportReason.FAKE_ITEM, "가품 같아요", null),
            )

        // when: 실제 HTTP로 신고 API를 호출한다(서비스·레포지토리 목킹 없음).
        mockMvc
            .perform(
                post("/api/products/${product.id}/reports")
                    .header("Authorization", token(reporter.id!!))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.reportType").value("PRODUCT"))
            .andExpect(jsonPath("$.data.reason").value("FAKE_ITEM"))
            .andExpect(jsonPath("$.data.status").value("RECEIVED"))

        // then: 응답뿐 아니라 실제 DB에도 반영됐는지 끝까지 확인한다.
        val saved = reportRepository.findAllByReporter(reporter).single()
        assertThat(saved.targetProduct?.id).isEqualTo(product.id)
        assertThat(saved.status).isEqualTo(ReportStatus.RECEIVED)
    }

    @Test
    fun `신고 접수 후 취소하면 응답은 200, 목록 조회에서도 사라지고 DB에서도 삭제된다`() {
        // given: 신고를 실제로 접수한다(응답에서 reportId를 얻어 이어지는 취소 요청에 쓴다).
        val product = saveProduct()
        val reporter = memberRepository.save(Member.createUser("report-e2e-canceler@example.com", "pw", "신고자"))
        val createBody =
            objectMapper.writeValueAsString(
                ProductReportCreateRequest(ReportReason.FAKE_ITEM, "가품 같아요", null),
            )
        val createResult =
            mockMvc
                .perform(
                    post("/api/products/${product.id}/reports")
                        .header("Authorization", token(reporter.id!!))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody),
                ).andExpect(status().isCreated())
                .andReturn()
        val reportId = objectMapper.readTree(createResult.response.contentAsString).at("/data/reportId").asLong()

        // when: 같은 사용자가 방금 접수한 신고를 취소한다.
        mockMvc
            .perform(delete("/api/members/me/reports/$reportId").header("Authorization", token(reporter.id!!)))
            .andExpect(status().isOk())

        // then: 목록 조회 응답에서도, 실제 DB에서도 더 이상 존재하지 않는다.
        mockMvc
            .perform(get("/api/members/me/reports").header("Authorization", token(reporter.id!!)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isEmpty())
        assertThat(reportRepository.findById(reportId)).isEmpty()
    }
}
