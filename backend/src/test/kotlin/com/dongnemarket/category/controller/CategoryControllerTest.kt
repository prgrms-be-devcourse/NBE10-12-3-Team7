package com.dongnemarket.category.controller

import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal

/**
 * [통합] 카테고리 REST — 목록·카테고리별 상품 목록. 둘 다 인증 없이 열려 있다.
 * 상품 목록은 최신 등록순이며 숨김(hide)·삭제(softDelete) 상품은 응답에서 빠져야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CategoryControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var categoryRepository: CategoryRepository

    @Autowired
    lateinit var productRepository: ProductRepository

    @Autowired
    lateinit var regionRepository: RegionRepository

    @AfterEach
    fun cleanUp() {
        productRepository.deleteAll()
        memberRepository.deleteAll()
    }

    @Test
    fun `카테고리 목록을 인증 없이 조회할 수 있다`() {
        mockMvc
            .perform(get("/api/categories"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.message").value("요청이 성공적으로 처리되었습니다."))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(8))
            .andExpect(jsonPath("$.data[0].id").isNumber())
            .andExpect(jsonPath("$.data[0].name").value("디지털기기"))
            .andExpect(jsonPath("$.data[7].name").value("기타"))
    }

    @Test
    fun `카테고리별 상품 목록은 인증 없이 최신 등록순으로 조회하고 숨김·삭제 상품은 제외한다`() {
        val member = memberRepository.save(Member.createUser("category-seller@example.com", "encodedPassword", "판매자"))
        val targetCategory = categoryRepository.findAllByOrderByIdAsc()[0]
        val otherCategory = categoryRepository.findAllByOrderByIdAsc()[1]
        val oldProduct =
            productRepository.save(
                Product.create(
                    member,
                    targetCategory,
                    "오래된 상품",
                    "오래된 상품 설명",
                    BigDecimal.valueOf(10000),
                    findRegion("1168010100"),
                ),
            )
        val newProduct =
            productRepository.save(
                Product.create(
                    member,
                    targetCategory,
                    "최신 상품",
                    "최신 상품 설명",
                    BigDecimal.valueOf(20000),
                    findRegion("1165010800"),
                ),
            )
        productRepository.save(
            Product.create(
                member,
                otherCategory,
                "다른 카테고리 상품",
                "다른 카테고리 상품 설명",
                BigDecimal.valueOf(30000),
                findRegion("1171010100"),
            ),
        )
        val hiddenProduct =
            Product.create(member, targetCategory, "숨김 상품", "숨김 상품 설명", BigDecimal.valueOf(40000), findRegion("1144012400"))
        hiddenProduct.hide()
        productRepository.save(hiddenProduct)
        val deletedProduct =
            Product.create(member, targetCategory, "삭제 상품", "삭제 상품 설명", BigDecimal.valueOf(50000), findRegion("1117013000"))
        deletedProduct.softDelete()
        productRepository.saveAndFlush(deletedProduct)

        mockMvc
            .perform(get("/api/categories/{categoryId}/products", targetCategory.id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].productId").value(newProduct.id))
            .andExpect(jsonPath("$.data[0].title").value("최신 상품"))
            .andExpect(jsonPath("$.data[0].description").doesNotExist())
            .andExpect(jsonPath("$.data[0].categoryId").value(targetCategory.id))
            .andExpect(jsonPath("$.data[1].productId").value(oldProduct.id))
            .andExpect(jsonPath("$.data[1].title").value("오래된 상품"))
    }

    @Test
    fun `존재하지 않는 카테고리 상품 목록 조회 시 CATEGORY_NOT_FOUND를 반환한다`() {
        mockMvc
            .perform(get("/api/categories/{categoryId}/products", 9999L))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("CATEGORY_NOT_FOUND"))
    }

    @Test
    fun `카테고리는 존재하지만 상품이 없으면 빈 목록을 반환한다`() {
        val category = categoryRepository.findAllByOrderByIdAsc()[7]

        mockMvc
            .perform(get("/api/categories/{categoryId}/products", category.id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    private fun findRegion(code: String): Region = regionRepository.findByCode(code).orElseThrow()
}
