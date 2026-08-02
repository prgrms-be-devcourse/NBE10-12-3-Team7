package com.dongnemarket.favorite.controller

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.favorite.repository.FavoriteRepository
import com.dongnemarket.global.security.jwt.JwtTokenProvider
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal

/**
 * 관심 상품 API 통합 테스트.
 *
 * 실제 HTTP 요청으로 사용자 유스케이스(성공·실패·엣지)를 검증한다.
 * 통신 계층만 MockMvc로 대체하고 Controller·Service·Repository는 실제로 동작한다(H2, MySQL/Docker 불필요).
 * 실제 JWT로 @AuthenticationPrincipal(memberId) 바인딩까지 검증한다.
 * 응답 예시는 이 테스트로 직접 확인한 값이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("관심 상품 API 통합 테스트")
class FavoriteControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var categoryRepository: CategoryRepository

    @Autowired
    lateinit var productRepository: ProductRepository

    @Autowired
    lateinit var regionRepository: RegionRepository

    @Autowired
    lateinit var favoriteRepository: FavoriteRepository

    private var productId = 0L
    private var otherProductId = 0L
    private var categoryId = 0L
    private lateinit var token: String
    private lateinit var sellerToken: String

    @BeforeEach
    fun setUp() {
        val buyer = memberRepository.save(Member.createUser("buyer@example.com", "encoded-pw", "buyer"))
        val seller = memberRepository.save(Member.createUser("seller@example.com", "encoded-pw", "seller"))
        // 시드된 기본 카테고리(CategorySeeder)와 이름이 겹치지 않도록 테스트 전용 카테고리를 만든다.
        val category = categoryRepository.save(Category("관심테스트전용카테고리"))
        val region = saveYeoksam()
        var product = Product.create(seller, category, "맥북 프로", "상태 좋음", BigDecimal.valueOf(1_500_000), region)
        product.changeThumbnailUrl("https://img.example/macbook.jpg")
        product = productRepository.save(product)
        val otherProduct =
            productRepository.save(
                Product.create(seller, category, "아이패드", "상태 좋음", BigDecimal.valueOf(700_000), region),
            )

        categoryId = category.id!!
        productId = product.id!!
        otherProductId = otherProduct.id!!
        token = "Bearer " + jwtTokenProvider.createAccessToken(buyer.id!!, "ROLE_USER")
        sellerToken = "Bearer " + jwtTokenProvider.createAccessToken(seller.id!!, "ROLE_USER")
    }

    @AfterEach
    fun cleanUp() {
        favoriteRepository.deleteAll()
        productRepository.deleteAll()
        memberRepository.deleteAll()
        // 시드 카테고리는 보존하고 테스트가 만든 카테고리만 제거한다.
        categoryRepository.deleteById(categoryId)
    }

    @Nested
    @DisplayName("관심 등록 (POST /api/products/{productId}/favorites)")
    inner class AddFavorite {
        @Test
        fun `로그인 사용자가 존재하는 상품을 관심 등록하면 201과 관심 정보를 반환한다`() {
            mockMvc
                .perform(
                    post("/api/products/{productId}/favorites", productId)
                        .header("Authorization", token),
                ).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.productId").value(productId.toInt()))
                .andExpect(jsonPath("$.data.id").exists())
        }

        @Test
        fun `토큰 없이 요청하면 401과 UNAUTHORIZED를 반환한다`() {
            mockMvc
                .perform(post("/api/products/{productId}/favorites", productId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
        }

        @Test
        fun `변조된 토큰으로 요청하면 401과 INVALID_TOKEN을 반환한다`() {
            mockMvc
                .perform(
                    post("/api/products/{productId}/favorites", productId)
                        .header("Authorization", token + "tampered"),
                ).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_TOKEN"))
        }

        @Test
        fun `존재하지 않는 상품을 등록하면 404와 PRODUCT_NOT_FOUND를 반환한다`() {
            mockMvc
                .perform(
                    post("/api/products/{productId}/favorites", 999_999L)
                        .header("Authorization", token),
                ).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"))
        }

        @Test
        fun `숨김 처리된 상품을 등록하면 404와 PRODUCT_NOT_FOUND를 반환한다`() {
            val hidden = productRepository.findById(productId).orElseThrow()
            hidden.hide()
            productRepository.saveAndFlush(hidden)

            mockMvc
                .perform(
                    post("/api/products/{productId}/favorites", productId)
                        .header("Authorization", token),
                ).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"))
        }

        @Test
        fun `삭제된 상품을 등록하면 404와 PRODUCT_NOT_FOUND를 반환한다`() {
            val deleted = productRepository.findById(productId).orElseThrow()
            deleted.softDelete()
            productRepository.saveAndFlush(deleted)

            mockMvc
                .perform(
                    post("/api/products/{productId}/favorites", productId)
                        .header("Authorization", token),
                ).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"))
        }

        @Test
        fun `판매자가 자기 상품을 관심 등록하면 400과 CANNOT_FAVORITE_OWN_PRODUCT를 반환한다`() {
            mockMvc
                .perform(
                    post("/api/products/{productId}/favorites", productId)
                        .header("Authorization", sellerToken),
                ).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CANNOT_FAVORITE_OWN_PRODUCT"))
        }

        @Test
        fun `이미 관심 등록한 상품을 다시 등록하면 409와 FAVORITE_ALREADY_EXISTS를 반환한다`() {
            mockMvc
                .perform(
                    post("/api/products/{productId}/favorites", productId)
                        .header("Authorization", token),
                ).andExpect(status().isCreated())

            mockMvc
                .perform(
                    post("/api/products/{productId}/favorites", productId)
                        .header("Authorization", token),
                ).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("FAVORITE_ALREADY_EXISTS"))
        }
    }

    @Nested
    @DisplayName("내 관심 목록 조회 (GET /api/members/me/favorites)")
    inner class GetMyFavorites {
        @Test
        fun `상품 요약을 포함한 최근 등록순 목록을 200으로 반환한다`() {
            mockMvc.perform(post("/api/products/{productId}/favorites", productId).header("Authorization", token))
            mockMvc.perform(post("/api/products/{productId}/favorites", otherProductId).header("Authorization", token))

            mockMvc
                .perform(
                    get("/api/members/me/favorites")
                        .header("Authorization", token),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].favoriteId").exists())
                .andExpect(jsonPath("$.data[0].product.productId").value(otherProductId.toInt()))
                .andExpect(jsonPath("$.data[0].product.title").value("아이패드"))
                .andExpect(jsonPath("$.data[0].product.region").doesNotExist())
                .andExpect(jsonPath("$.data[0].product.regionCode").value("1168010100"))
                .andExpect(jsonPath("$.data[0].product.regionName").value("역삼동"))
                .andExpect(jsonPath("$.data[0].product.regionFullName").value("서울특별시 강남구 역삼동"))
                .andExpect(jsonPath("$.data[0].product.tradeStatus").value("ON_SALE"))
                .andExpect(jsonPath("$.data[1].product.productId").value(productId.toInt()))
                .andExpect(jsonPath("$.data[1].product.title").value("맥북 프로"))
                .andExpect(jsonPath("$.data[1].product.regionCode").value("1168010100"))
                .andExpect(jsonPath("$.data[1].product.thumbnailUrl").value("https://img.example/macbook.jpg"))
        }

        @Test
        fun `삭제된 상품은 관심 목록에서 제외된다`() {
            mockMvc.perform(post("/api/products/{productId}/favorites", productId).header("Authorization", token))
            mockMvc.perform(post("/api/products/{productId}/favorites", otherProductId).header("Authorization", token))

            // productId 상품을 삭제 → 관심 row는 남지만 목록에서는 제외되어야 한다.
            val deleted = productRepository.findById(productId).orElseThrow()
            deleted.softDelete()
            productRepository.saveAndFlush(deleted)

            mockMvc
                .perform(
                    get("/api/members/me/favorites")
                        .header("Authorization", token),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].product.productId").value(otherProductId.toInt()))
        }

        @Test
        fun `숨김 처리된 상품은 관심 목록에서 제외된다`() {
            mockMvc.perform(post("/api/products/{productId}/favorites", productId).header("Authorization", token))
            mockMvc.perform(post("/api/products/{productId}/favorites", otherProductId).header("Authorization", token))

            // productId 상품을 숨김 → 관심 row는 남지만 목록에서는 제외되어야 한다.
            val hidden = productRepository.findById(productId).orElseThrow()
            hidden.hide()
            productRepository.saveAndFlush(hidden)

            mockMvc
                .perform(
                    get("/api/members/me/favorites")
                        .header("Authorization", token),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].product.productId").value(otherProductId.toInt()))
        }

        @Test
        fun `관심 상품이 없으면 200과 빈 배열을 반환한다`() {
            mockMvc
                .perform(
                    get("/api/members/me/favorites")
                        .header("Authorization", token),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.length()").value(0))
        }

        @Test
        fun `토큰 없이 조회하면 401과 UNAUTHORIZED를 반환한다`() {
            mockMvc
                .perform(get("/api/members/me/favorites"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
        }
    }

    @Nested
    @DisplayName("관심 취소 (DELETE /api/products/{productId}/favorites)")
    inner class RemoveFavorite {
        @Test
        fun `등록한 관심 상품을 취소하면 200을 반환한다`() {
            mockMvc
                .perform(
                    post("/api/products/{productId}/favorites", productId)
                        .header("Authorization", token),
                ).andExpect(status().isCreated())

            mockMvc
                .perform(
                    delete("/api/products/{productId}/favorites", productId)
                        .header("Authorization", token),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
        }

        @Test
        fun `등록하지 않은 상품을 취소하면 404와 FAVORITE_NOT_FOUND를 반환한다`() {
            mockMvc
                .perform(
                    delete("/api/products/{productId}/favorites", productId)
                        .header("Authorization", token),
                ).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("FAVORITE_NOT_FOUND"))
        }

        @Test
        fun `토큰 없이 요청하면 401과 UNAUTHORIZED를 반환한다`() {
            mockMvc
                .perform(delete("/api/products/{productId}/favorites", productId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
        }
    }

    private fun saveYeoksam(): Region {
        val seoul =
            regionRepository
                .findByCode("1100000000")
                .orElseGet { regionRepository.save(Region.root("1100000000", "서울특별시", "서울특별시")) }
        val gangnam =
            regionRepository
                .findByCode("1168000000")
                .orElseGet { regionRepository.save(Region.child("1168000000", 2, seoul, "서울특별시 강남구", "강남구")) }
        return regionRepository
            .findByCode("1168010100")
            .orElseGet { regionRepository.save(Region.child("1168010100", 3, gangnam, "서울특별시 강남구 역삼동", "역삼동")) }
    }
}
