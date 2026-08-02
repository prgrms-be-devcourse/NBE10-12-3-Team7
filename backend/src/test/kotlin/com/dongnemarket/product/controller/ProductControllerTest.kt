package com.dongnemarket.product.controller

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.global.security.jwt.JwtTokenProvider
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.ProductImage
import com.dongnemarket.product.entity.TradeStatus
import com.dongnemarket.product.repository.ProductImageRepository
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal

/**
 * [통합] 상품 REST 전체 — 등록·목록·검색·내 상품·상세·수정·상태변경·삭제.
 *
 * 응답 JSON 필드명이 프론트와의 계약이라 `$.data.hidden`·`$.data.hasNext` 처럼
 * 이름 자체를 단언한다. DTO 프로퍼티명을 바꾸면 여기서 깨지도록 의도된 것이다
 * ([com.dongnemarket.product.dto.ProductSummaryResponse] 주석 참고).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductControllerTest {
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
    lateinit var productImageRepository: ProductImageRepository

    @Autowired
    lateinit var regionRepository: RegionRepository

    @Autowired
    lateinit var entityManager: EntityManager

    @AfterEach
    fun cleanUp() {
        productImageRepository.deleteAll()
        productRepository.deleteAll()
        categoryRepository.deleteAll()
        memberRepository.deleteAll()
    }

    @Test
    fun `인증 없이 상품 등록 요청 시 401을 반환한다`() {
        val body =
            """
            {
              "categoryId": 1,
              "title": "아이폰 15",
              "description": "상태 좋은 아이폰입니다.",
              "price": 800000,
              "regionCode": "1168010100"
            }
            """.trimIndent()

        mockMvc
            .perform(
                post("/api/products")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
    }

    @Test
    fun `인증된 사용자는 상품을 등록할 수 있다`() {
        val member = memberRepository.save(Member.createUser("seller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리1"))
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "categoryId": ${category.id},
              "title": "아이폰 15",
              "description": "상태 좋은 아이폰입니다.",
              "price": 800000,
              "regionCode": "1168010100",
              "imageUrls": ["https://example.com/product-1.jpg"],
              "thumbnailIndex": 0
            }
            """.trimIndent()

        mockMvc
            .perform(
                post("/api/products")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value(201))
            .andExpect(jsonPath("$.data.productId").exists())
            .andExpect(jsonPath("$.data.memberId").value(member.id))
            .andExpect(jsonPath("$.data.categoryId").value(category.id))
            .andExpect(jsonPath("$.data.title").value("아이폰 15"))
            .andExpect(jsonPath("$.data.description").value("상태 좋은 아이폰입니다."))
            .andExpect(jsonPath("$.data.price").value(800000))
            .andExpect(jsonPath("$.data.tradeStatus").value("ON_SALE"))
            .andExpect(jsonPath("$.data.region").doesNotExist())
            .andExpect(jsonPath("$.data.regionCode").value("1168010100"))
            .andExpect(jsonPath("$.data.regionName").value("역삼동"))
            .andExpect(jsonPath("$.data.regionFullName").value("서울특별시 강남구 역삼동"))
            .andExpect(jsonPath("$.data.viewCount").value(0))
            .andExpect(jsonPath("$.data.hidden").value(false))
    }

    @Test
    fun `존재하지 않는 카테고리로 상품 등록 시 CATEGORY_NOT_FOUND를 반환한다`() {
        val member = memberRepository.save(Member.createUser("seller@example.com", "encodedPassword", "판매자"))
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "categoryId": 999,
              "title": "아이폰 15",
              "description": "상태 좋은 아이폰입니다.",
              "price": 800000,
              "regionCode": "1168010100",
              "imageUrls": ["https://example.com/product-1.jpg"],
              "thumbnailIndex": 0
            }
            """.trimIndent()

        mockMvc
            .perform(
                post("/api/products")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("CATEGORY_NOT_FOUND"))
    }

    @Test
    fun `제목이 비어 있으면 INVALID_PRODUCT_TITLE을 반환한다`() {
        val member = memberRepository.save(Member.createUser("seller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리2"))
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "categoryId": ${category.id},
              "title": " ",
              "description": "상태 좋은 아이폰입니다.",
              "price": 800000,
              "regionCode": "1168010100",
              "imageUrls": ["https://example.com/product-1.jpg"],
              "thumbnailIndex": 0
            }
            """.trimIndent()

        mockMvc
            .perform(
                post("/api/products")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_PRODUCT_TITLE"))
    }

    @Test
    fun `가격이 음수이면 INVALID_PRODUCT_PRICE를 반환한다`() {
        val member = memberRepository.save(Member.createUser("seller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리3"))
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "categoryId": ${category.id},
              "title": "아이폰 15",
              "description": "상태 좋은 아이폰입니다.",
              "price": -1,
              "regionCode": "1168010100",
              "imageUrls": ["https://example.com/product-1.jpg"],
              "thumbnailIndex": 0
            }
            """.trimIndent()

        mockMvc
            .perform(
                post("/api/products")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_PRODUCT_PRICE"))
    }

    @Test
    fun `이미지 URL이 공백이면 INVALID_INPUT_VALUE를 반환한다`() {
        val member = memberRepository.save(Member.createUser("seller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리이미지"))
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "categoryId": ${category.id},
              "title": "아이폰 15",
              "description": "상태 좋은 아이폰입니다.",
              "price": 800000,
              "regionCode": "1168010100",
              "imageUrls": ["https://example.com/1.jpg", " "],
              "thumbnailIndex": 0
            }
            """.trimIndent()

        mockMvc
            .perform(
                post("/api/products")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    @Test
    fun `지역 마스터에 없는 지역으로 상품 등록 시 INVALID_INPUT_VALUE를 반환한다`() {
        val member = memberRepository.save(Member.createUser("unknown-region-seller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리미등록지역"))
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "categoryId": ${category.id},
              "title": "아이폰 15",
              "description": "상태 좋은 아이폰입니다.",
              "price": 800000,
              "regionCode": "9999999999",
              "imageUrls": ["https://example.com/product-1.jpg"],
              "thumbnailIndex": 0
            }
            """.trimIndent()

        mockMvc
            .perform(
                post("/api/products")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    @Test
    fun `level 3이 아닌 지역 코드로 상품 등록 시 INVALID_INPUT_VALUE를 반환한다`() {
        val member = memberRepository.save(Member.createUser("level2-region-seller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리시군구지역"))
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "categoryId": ${category.id},
              "title": "아이폰 15",
              "description": "상태 좋은 아이폰입니다.",
              "price": 800000,
              "regionCode": "1168000000",
              "imageUrls": ["https://example.com/product-1.jpg"],
              "thumbnailIndex": 0
            }
            """.trimIndent()

        mockMvc
            .perform(
                post("/api/products")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    @Test
    fun `상품 목록은 인증 없이 최신 등록순으로 조회하고 숨김·삭제 상품은 제외한다`() {
        val member = memberRepository.save(Member.createUser("seller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리4"))
        val oldProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
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
                    category,
                    "최신 상품",
                    "최신 상품 설명",
                    BigDecimal.valueOf(20000),
                    findRegion("1165010800"),
                ),
            )
        val hiddenProduct =
            Product.create(member, category, "숨김 상품", "숨김 상품 설명", BigDecimal.valueOf(30000), findRegion("1171010100"))
        hiddenProduct.hide()
        productRepository.save(hiddenProduct)
        val deletedProduct =
            Product.create(member, category, "삭제 상품", "삭제 상품 설명", BigDecimal.valueOf(40000), findRegion("1144012400"))
        deletedProduct.softDelete()
        productRepository.saveAndFlush(deletedProduct)

        mockMvc
            .perform(get("/api/products"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].productId").value(newProduct.id))
            .andExpect(jsonPath("$.data.items[0].title").value("최신 상품"))
            .andExpect(jsonPath("$.data.items[0].description").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].price").value(20000))
            .andExpect(jsonPath("$.data.items[0].tradeStatus").value("ON_SALE"))
            .andExpect(jsonPath("$.data.items[0].region").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].viewCount").value(0))
            .andExpect(jsonPath("$.data.items[0].hidden").value(false))
            .andExpect(jsonPath("$.data.items[1].productId").value(oldProduct.id))
            .andExpect(jsonPath("$.data.items[1].title").value("오래된 상품"))
            .andExpect(jsonPath("$.data.hasNext").value(false))
            .andExpect(jsonPath("$.data.nextCursor").doesNotExist())
    }

    @Test
    fun `상품 목록은 탈퇴·정지 판매자 상품과 거래완료 상품을 제외한다`() {
        val activeMember = memberRepository.save(Member.createUser("list-visible-active@example.com", "encodedPassword", "활성판매자"))
        val deletedMember =
            memberRepository.save(
                Member.createUser("list-visible-deleted@example.com", "encodedPassword", "탈퇴판매자").apply {
                    changeStatus(MemberStatus.DELETED)
                },
            )
        val suspendedMember =
            memberRepository.save(
                Member.createUser("list-visible-suspended@example.com", "encodedPassword", "정지판매자").apply {
                    changeStatus(MemberStatus.SUSPENDED)
                },
            )
        val category = categoryRepository.save(Category("목록공개정책"))
        val onSaleProduct =
            productRepository.save(
                Product.create(
                    activeMember,
                    category,
                    "판매중 공개 상품",
                    "판매중 공개 상품 설명",
                    BigDecimal.valueOf(10000),
                    findRegion("1168010100"),
                ),
            )
        val reservedProduct =
            Product.create(activeMember, category, "예약중 공개 상품", "예약중 공개 상품 설명", BigDecimal.valueOf(20000), findRegion("1144012400"))
        reservedProduct.changeTradeStatus(TradeStatus.RESERVED)
        val savedReservedProduct = productRepository.save(reservedProduct)
        val completedProduct =
            Product.create(activeMember, category, "거래완료 상품", "거래완료 상품 설명", BigDecimal.valueOf(30000), findRegion("1165010800"))
        completedProduct.complete()
        productRepository.save(completedProduct)
        productRepository.save(
            Product.create(deletedMember, category, "탈퇴 판매자 상품", "탈퇴 판매자 상품 설명", BigDecimal.valueOf(40000), findRegion("1171010100")),
        )
        productRepository.saveAndFlush(
            Product.create(suspendedMember, category, "정지 판매자 상품", "정지 판매자 상품 설명", BigDecimal.valueOf(50000), findRegion("1117013000")),
        )

        mockMvc
            .perform(get("/api/products"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].productId").value(savedReservedProduct.id))
            .andExpect(jsonPath("$.data.items[1].productId").value(onSaleProduct.id))
            .andExpect(jsonPath("$.data.items[?(@.productId == ${completedProduct.id})]").isEmpty())
    }

    @Test
    fun `상품 목록은 지역 2개로 필터링해 최신 등록순으로 조회한다`() {
        val member = memberRepository.save(Member.createUser("region-list-controller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리지역목록"))
        val gangnam = findRegion("1168010100")
        val mapo = findRegion("1144012400")
        val songpa = findRegion("1171010100")
        val gangnamProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "강남 상품",
                    "강남 상품 설명",
                    BigDecimal.valueOf(10000),
                    gangnam,
                ),
            )
        val mapoProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "마포 상품",
                    "마포 상품 설명",
                    BigDecimal.valueOf(20000),
                    mapo,
                ),
            )
        productRepository.saveAndFlush(
            Product.create(
                member,
                category,
                "송파 상품",
                "송파 상품 설명",
                BigDecimal.valueOf(30000),
                songpa,
            ),
        )

        mockMvc
            .perform(
                get("/api/products")
                    .param("regionCodes", "1168000000", "1144000000"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].productId").value(mapoProduct.id))
            .andExpect(jsonPath("$.data.items[1].productId").value(gangnamProduct.id))
            .andExpect(jsonPath("$.data.hasNext").value(false))
            .andExpect(jsonPath("$.data.nextCursor").doesNotExist())
    }

    @Test
    fun `상품 목록은 커서로 다음 페이지를 이어 조회한다`() {
        val member = memberRepository.save(Member.createUser("cursor-list-controller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리커서목록"))
        val firstProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "첫번째 상품",
                    "첫번째 상품 설명",
                    BigDecimal.valueOf(10000),
                    findRegion("1168010100"),
                ),
            )
        val secondProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "두번째 상품",
                    "두번째 상품 설명",
                    BigDecimal.valueOf(20000),
                    findRegion("1168010100"),
                ),
            )
        val thirdProduct =
            productRepository.saveAndFlush(
                Product.create(
                    member,
                    category,
                    "세번째 상품",
                    "세번째 상품 설명",
                    BigDecimal.valueOf(30000),
                    findRegion("1168010100"),
                ),
            )

        mockMvc
            .perform(
                get("/api/products")
                    .param("size", "2"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].productId").value(thirdProduct.id))
            .andExpect(jsonPath("$.data.items[1].productId").value(secondProduct.id))
            .andExpect(jsonPath("$.data.hasNext").value(true))
            .andExpect(jsonPath("$.data.nextCursor").value(secondProduct.id))

        mockMvc
            .perform(
                get("/api/products")
                    .param("size", "2")
                    .param("cursor", secondProduct.id.toString()),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].productId").value(firstProduct.id))
            .andExpect(jsonPath("$.data.hasNext").value(false))
            .andExpect(jsonPath("$.data.nextCursor").doesNotExist())
    }

    @Test
    fun `상품 목록은 지역 필터와 커서를 함께 적용한다`() {
        val member = memberRepository.save(Member.createUser("cursor-region-list-controller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리커서지역목록"))
        val gangnam = findRegion("1168010100")
        val mapo = findRegion("1144012400")
        val songpa = findRegion("1171010100")
        val gangnamOldProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "강남 오래된 상품",
                    "강남 오래된 상품 설명",
                    BigDecimal.valueOf(10000),
                    gangnam,
                ),
            )
        val mapoProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "마포 상품",
                    "마포 상품 설명",
                    BigDecimal.valueOf(20000),
                    mapo,
                ),
            )
        productRepository.save(
            Product.create(
                member,
                category,
                "송파 상품",
                "송파 상품 설명",
                BigDecimal.valueOf(30000),
                songpa,
            ),
        )
        val gangnamNewProduct =
            productRepository.saveAndFlush(
                Product.create(
                    member,
                    category,
                    "강남 최신 상품",
                    "강남 최신 상품 설명",
                    BigDecimal.valueOf(40000),
                    gangnam,
                ),
            )

        mockMvc
            .perform(
                get("/api/products")
                    .param("regionCodes", "1168000000")
                    .param("cursor", gangnamNewProduct.id.toString())
                    .param("size", "2"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].productId").value(gangnamOldProduct.id))
            .andExpect(jsonPath("$.data.hasNext").value(false))
            .andExpect(jsonPath("$.data.nextCursor").doesNotExist())
            .andExpect(jsonPath("$.data.items[?(@.productId == ${mapoProduct.id})]").isEmpty())
    }

    @Test
    fun `상품 목록 size가 0 이하이면 기본 크기로 조회한다`() {
        val member = memberRepository.save(Member.createUser("cursor-default-size-controller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리기본크기"))
        for (i in 1..31) {
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "상품 $i",
                    "상품 설명 $i",
                    BigDecimal.valueOf(i * 1000L),
                    findRegion("1168010100"),
                ),
            )
        }
        productRepository.flush()

        mockMvc
            .perform(
                get("/api/products")
                    .param("size", "0"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(30))
            .andExpect(jsonPath("$.data.hasNext").value(true))
    }

    @Test
    fun `상품 목록 size가 100보다 크면 최대 100개로 제한한다`() {
        val member = memberRepository.save(Member.createUser("cursor-max-size-controller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리최대크기"))
        for (i in 1..101) {
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "상품 $i",
                    "상품 설명 $i",
                    BigDecimal.valueOf(i * 1000L),
                    findRegion("1168010100"),
                ),
            )
        }
        productRepository.flush()

        mockMvc
            .perform(
                get("/api/products")
                    .param("size", "101"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items.length()").value(100))
            .andExpect(jsonPath("$.data.hasNext").value(true))
    }

    @Test
    fun `상품 목록 regionCode 필터가 3개이면 INVALID_INPUT_VALUE를 반환한다`() {
        mockMvc
            .perform(
                get("/api/products")
                    .param("regionCodes", "1168000000", "1144000000", "1171000000"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    @Test
    fun `상품 검색은 인증 없이 조건에 맞는 상품을 최신 등록순으로 조회한다`() {
        val member = memberRepository.save(Member.createUser("search-controller@example.com", "encodedPassword", "판매자"))
        val targetCategory = categoryRepository.save(Category("검색카테고리1"))
        val otherCategory = categoryRepository.save(Category("검색카테고리2"))
        val oldProduct =
            Product.create(
                member,
                targetCategory,
                "맥북 에어",
                "가벼운 맥북입니다.",
                BigDecimal.valueOf(1000000),
                findRegion("1168010100"),
            )
        oldProduct.changeTradeStatus(TradeStatus.RESERVED)
        val savedOldProduct = productRepository.save(oldProduct)
        val newProduct =
            Product.create(
                member,
                targetCategory,
                "맥북 프로",
                "성능 좋은 맥북입니다.",
                BigDecimal.valueOf(1500000),
                findRegion("1165010800"),
            )
        newProduct.changeTradeStatus(TradeStatus.RESERVED)
        val savedNewProduct = productRepository.save(newProduct)
        productRepository.save(
            Product.create(member, otherCategory, "맥북 관련 책", "맥북 설명서입니다.", BigDecimal.valueOf(20000), findRegion("1171010100")),
        )
        val hiddenProduct =
            Product.create(member, targetCategory, "숨김 맥북", "숨김 상품입니다.", BigDecimal.valueOf(1200000), findRegion("1144012400"))
        hiddenProduct.hide()
        productRepository.save(hiddenProduct)
        val deletedProduct =
            Product.create(member, targetCategory, "삭제 맥북", "삭제 상품입니다.", BigDecimal.valueOf(1300000), findRegion("1117013000"))
        deletedProduct.softDelete()
        productRepository.saveAndFlush(deletedProduct)

        mockMvc
            .perform(
                get("/api/products/search")
                    .param("keyword", "맥북")
                    .param("categoryId", targetCategory.id.toString())
                    .param("minPrice", "900000")
                    .param("maxPrice", "1600000")
                    .param("tradeStatus", "RESERVED"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].productId").value(savedNewProduct.id))
            .andExpect(jsonPath("$.data[0].title").value("맥북 프로"))
            .andExpect(jsonPath("$.data[0].tradeStatus").value("RESERVED"))
            .andExpect(jsonPath("$.data[0].hidden").value(false))
            .andExpect(jsonPath("$.data[1].productId").value(savedOldProduct.id))
            .andExpect(jsonPath("$.data[1].title").value("맥북 에어"))
    }

    @Test
    fun `상품 검색은 키워드와 지역 필터를 함께 적용한다`() {
        val member = memberRepository.save(Member.createUser("search-region-controller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("검색지역카테고리"))
        val gangnam = findRegion("1168010100")
        val songpa = findRegion("1171010100")
        val matchedProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "맥북 프로",
                    "상태 좋은 노트북입니다.",
                    BigDecimal.valueOf(1500000),
                    gangnam,
                ),
            )
        productRepository.save(
            Product.create(
                member,
                category,
                "맥북 에어",
                "가벼운 노트북입니다.",
                BigDecimal.valueOf(1000000),
                songpa,
            ),
        )
        productRepository.saveAndFlush(
            Product.create(
                member,
                category,
                "아이패드",
                "상태 좋은 태블릿입니다.",
                BigDecimal.valueOf(700000),
                gangnam,
            ),
        )

        mockMvc
            .perform(
                get("/api/products/search")
                    .param("keyword", "맥북")
                    .param("regionCodes", "1168000000"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].productId").value(matchedProduct.id))
    }

    @Test
    fun `상품 검색은 탈퇴·정지 판매자 상품과 거래완료 상품을 제외한다`() {
        val activeMember =
            memberRepository.save(Member.createUser("search-visible-active-controller@example.com", "encodedPassword", "활성검색판매자"))
        val deletedMember =
            memberRepository.save(
                Member.createUser("search-visible-deleted-controller@example.com", "encodedPassword", "탈퇴검색판매자").apply {
                    changeStatus(MemberStatus.DELETED)
                },
            )
        val suspendedMember =
            memberRepository.save(
                Member.createUser("search-visible-suspended-controller@example.com", "encodedPassword", "정지검색판매자").apply {
                    changeStatus(MemberStatus.SUSPENDED)
                },
            )
        val category = categoryRepository.save(Category("검색공개정책"))
        val gangnam = findRegion("1168010100")
        val visibleProduct =
            productRepository.save(
                Product.create(
                    activeMember,
                    category,
                    "정책 맥북",
                    "정책 검색 상품입니다.",
                    BigDecimal.valueOf(1000000),
                    gangnam,
                ),
            )
        val completedProduct =
            Product.create(activeMember, category, "정책 완료 맥북", "거래완료 검색 상품입니다.", BigDecimal.valueOf(900000), gangnam)
        completedProduct.complete()
        productRepository.save(completedProduct)
        productRepository.save(
            Product.create(deletedMember, category, "정책 탈퇴 맥북", "탈퇴 판매자 검색 상품입니다.", BigDecimal.valueOf(800000), gangnam),
        )
        productRepository.saveAndFlush(
            Product.create(suspendedMember, category, "정책 정지 맥북", "정지 판매자 검색 상품입니다.", BigDecimal.valueOf(700000), gangnam),
        )

        mockMvc
            .perform(
                get("/api/products/search")
                    .param("keyword", "정책")
                    .param("regionCodes", "1168000000"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].productId").value(visibleProduct.id))
            .andExpect(jsonPath("$.data[0].title").value("정책 맥북"))
    }

    @Test
    fun `상품 검색은 거래완료 상태를 요청하면 빈 목록을 반환한다`() {
        val member = memberRepository.save(Member.createUser("search-completed-controller@example.com", "encodedPassword", "완료검색판매자"))
        val category = categoryRepository.save(Category("검색거래완료"))
        val completedProduct =
            Product.create(
                member,
                category,
                "거래완료 맥북",
                "거래완료 검색 상품입니다.",
                BigDecimal.valueOf(1000000),
                findRegion("1168010100"),
            )
        completedProduct.complete()
        productRepository.saveAndFlush(completedProduct)

        mockMvc
            .perform(
                get("/api/products/search")
                    .param("keyword", "맥북")
                    .param("tradeStatus", "COMPLETED"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    @Test
    fun `상품 검색 regionCode 필터가 3개이면 INVALID_INPUT_VALUE를 반환한다`() {
        mockMvc
            .perform(
                get("/api/products/search")
                    .param("regionCodes", "1168000000", "1144000000", "1171000000"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_INPUT_VALUE"))
    }

    @Test
    fun `상품 검색 결과가 없으면 빈 목록을 반환한다`() {
        mockMvc
            .perform(
                get("/api/products/search")
                    .param("keyword", "없는상품"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    @Test
    fun `상품 검색 가격 조건이 잘못되면 INVALID_SEARCH_CONDITION을 반환한다`() {
        mockMvc
            .perform(
                get("/api/products/search")
                    .param("minPrice", "20000")
                    .param("maxPrice", "10000"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_SEARCH_CONDITION"))
    }

    @Test
    fun `상품 검색 거래 상태가 잘못되면 INVALID_TRADE_STATUS를 반환한다`() {
        mockMvc
            .perform(
                get("/api/products/search")
                    .param("tradeStatus", "INVALID"),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_TRADE_STATUS"))
    }

    @Test
    fun `상품 검색 거래 상태가 공백이면 INVALID_TRADE_STATUS를 반환한다`() {
        mockMvc
            .perform(
                get("/api/products/search")
                    .param("tradeStatus", " "),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_TRADE_STATUS"))
    }

    @Test
    fun `인증된 사용자는 내 상품 목록을 최신 등록순으로 조회하고 숨김 상품도 확인할 수 있다`() {
        val member = memberRepository.save(Member.createUser("my-products@example.com", "encodedPassword", "판매자"))
        val otherMember = memberRepository.save(Member.createUser("other-products@example.com", "encodedPassword", "다른판매자"))
        val category = categoryRepository.save(Category("테스트카테고리5"))
        val oldProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "오래된 내 상품",
                    "오래된 내 상품 설명",
                    BigDecimal.valueOf(10000),
                    findRegion("1168010100"),
                ),
            )
        val hiddenProduct =
            Product.create(member, category, "숨김 내 상품", "숨김 내 상품 설명", BigDecimal.valueOf(20000), findRegion("1165010800"))
        hiddenProduct.hide()
        val savedHiddenProduct = productRepository.save(hiddenProduct)
        productRepository.save(
            Product.create(
                otherMember,
                category,
                "다른 회원 상품",
                "다른 회원 상품 설명",
                BigDecimal.valueOf(30000),
                findRegion("1171010100"),
            ),
        )
        val deletedProduct =
            Product.create(member, category, "삭제 내 상품", "삭제 내 상품 설명", BigDecimal.valueOf(40000), findRegion("1144012400"))
        deletedProduct.softDelete()
        productRepository.saveAndFlush(deletedProduct)
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)

        mockMvc
            .perform(
                get("/api/products/me")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].productId").value(savedHiddenProduct.id))
            .andExpect(jsonPath("$.data[0].memberId").value(member.id))
            .andExpect(jsonPath("$.data[0].categoryId").value(category.id))
            .andExpect(jsonPath("$.data[0].title").value("숨김 내 상품"))
            .andExpect(jsonPath("$.data[0].price").value(20000))
            .andExpect(jsonPath("$.data[0].tradeStatus").value("ON_SALE"))
            .andExpect(jsonPath("$.data[0].region").doesNotExist())
            .andExpect(jsonPath("$.data[0].viewCount").value(0))
            .andExpect(jsonPath("$.data[0].hidden").value(true))
            .andExpect(jsonPath("$.data[1].productId").value(oldProduct.id))
            .andExpect(jsonPath("$.data[1].title").value("오래된 내 상품"))
            .andExpect(jsonPath("$.data[1].hidden").value(false))
    }

    @Test
    fun `내 상품이 없으면 빈 목록을 반환한다`() {
        val member = memberRepository.save(Member.createUser("empty-products@example.com", "encodedPassword", "판매자"))
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)

        mockMvc
            .perform(
                get("/api/products/me")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    @Test
    fun `인증 없이 내 상품 목록 조회 요청 시 401을 반환한다`() {
        mockMvc
            .perform(get("/api/products/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
    }

    @Test
    fun `상품 상세는 인증 없이 조회할 수 있고 조회수가 1 증가한다`() {
        val member = memberRepository.save(Member.createUser("seller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리6"))
        val product =
            productRepository.saveAndFlush(
                Product.create(
                    member,
                    category,
                    "아이폰 15",
                    "상태 좋은 아이폰입니다.",
                    BigDecimal.valueOf(800000),
                    findRegion("1168010100"),
                ),
            )

        mockMvc
            .perform(get("/api/products/{productId}", product.id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.productId").value(product.id))
            .andExpect(jsonPath("$.data.memberId").value(member.id))
            .andExpect(jsonPath("$.data.categoryId").value(category.id))
            .andExpect(jsonPath("$.data.title").value("아이폰 15"))
            .andExpect(jsonPath("$.data.description").value("상태 좋은 아이폰입니다."))
            .andExpect(jsonPath("$.data.price").value(800000))
            .andExpect(jsonPath("$.data.tradeStatus").value("ON_SALE"))
            .andExpect(jsonPath("$.data.region").doesNotExist())
            .andExpect(jsonPath("$.data.viewCount").value(1))
            .andExpect(jsonPath("$.data.hidden").value(false))
    }

    @Test
    fun `존재하지 않는 상품 상세 조회 시 PRODUCT_NOT_FOUND를 반환한다`() {
        mockMvc
            .perform(get("/api/products/{productId}", 999L))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"))
    }

    @Test
    fun `삭제된 상품 상세 조회 시 DELETED_PRODUCT를 반환한다`() {
        val member = memberRepository.save(Member.createUser("seller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리7"))
        val product =
            Product.create(member, category, "삭제 상품", "삭제 상품 설명", BigDecimal.valueOf(40000), findRegion("1144012400"))
        product.softDelete()
        val savedProduct = productRepository.saveAndFlush(product)

        mockMvc
            .perform(get("/api/products/{productId}", savedProduct.id))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("DELETED_PRODUCT"))
    }

    @Test
    fun `숨김 상품 상세 조회 시 HIDDEN_PRODUCT를 반환한다`() {
        val member = memberRepository.save(Member.createUser("seller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리8"))
        val product =
            Product.create(member, category, "숨김 상품", "숨김 상품 설명", BigDecimal.valueOf(30000), findRegion("1171010100"))
        product.hide()
        val savedProduct = productRepository.saveAndFlush(product)

        mockMvc
            .perform(get("/api/products/{productId}", savedProduct.id))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("HIDDEN_PRODUCT"))
    }

    @Test
    fun `탈퇴 판매자 상품 상세 조회 시 PRODUCT_NOT_FOUND를 반환한다`() {
        val member =
            memberRepository.save(
                Member.createUser("detail-deleted-seller@example.com", "encodedPassword", "탈퇴판매자").apply {
                    changeStatus(MemberStatus.DELETED)
                },
            )
        val category = categoryRepository.save(Category("상세탈퇴판매자"))
        val product =
            productRepository.saveAndFlush(
                Product.create(
                    member,
                    category,
                    "탈퇴 판매자 상품",
                    "탈퇴 판매자 상품 설명",
                    BigDecimal.valueOf(10000),
                    findRegion("1168010100"),
                ),
            )

        mockMvc
            .perform(get("/api/products/{productId}", product.id))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"))
    }

    @Test
    fun `정지 판매자 상품 상세 조회 시 PRODUCT_NOT_FOUND를 반환한다`() {
        val member =
            memberRepository.save(
                Member.createUser("detail-suspended-seller@example.com", "encodedPassword", "정지판매자").apply {
                    changeStatus(MemberStatus.SUSPENDED)
                },
            )
        val category = categoryRepository.save(Category("상세정지판매자"))
        val product =
            productRepository.saveAndFlush(
                Product.create(
                    member,
                    category,
                    "정지 판매자 상품",
                    "정지 판매자 상품 설명",
                    BigDecimal.valueOf(10000),
                    findRegion("1168010100"),
                ),
            )

        mockMvc
            .perform(get("/api/products/{productId}", product.id))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"))
    }

    @Test
    fun `거래완료 상품 상세 조회 시 PRODUCT_NOT_FOUND를 반환한다`() {
        val member = memberRepository.save(Member.createUser("detail-completed-seller@example.com", "encodedPassword", "완료판매자"))
        val category = categoryRepository.save(Category("상세거래완료"))
        val product =
            Product.create(
                member,
                category,
                "거래완료 상품",
                "거래완료 상품 설명",
                BigDecimal.valueOf(10000),
                findRegion("1168010100"),
            )
        product.complete()
        val savedProduct = productRepository.saveAndFlush(product)

        mockMvc
            .perform(get("/api/products/{productId}", savedProduct.id))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("PRODUCT_NOT_FOUND"))
    }

    @Test
    fun `작성자는 상품 정보를 수정할 수 있다`() {
        val member = memberRepository.save(Member.createUser("seller@example.com", "encodedPassword", "판매자"))
        val oldCategory = categoryRepository.save(Category("테스트카테고리9"))
        val newCategory = categoryRepository.save(Category("테스트카테고리10"))
        val product =
            productRepository.saveAndFlush(
                Product.create(
                    member,
                    oldCategory,
                    "아이폰 15",
                    "상태 좋은 아이폰입니다.",
                    BigDecimal.valueOf(800000),
                    findRegion("1168010100"),
                ),
            )
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "categoryId": ${newCategory.id},
              "title": "맥북 프로",
              "description": "수정된 상품 설명입니다.",
              "price": 1500000,
              "regionCode": "1165010800",
              "imageUrls": ["https://example.com/update-1.jpg"],
              "thumbnailIndex": 0
            }
            """.trimIndent()

        mockMvc
            .perform(
                patch("/api/products/{productId}", product.id)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.productId").value(product.id))
            .andExpect(jsonPath("$.data.categoryId").value(newCategory.id))
            .andExpect(jsonPath("$.data.title").value("맥북 프로"))
            .andExpect(jsonPath("$.data.description").value("수정된 상품 설명입니다."))
            .andExpect(jsonPath("$.data.price").value(1500000))
            .andExpect(jsonPath("$.data.region").doesNotExist())
            .andExpect(jsonPath("$.data.regionCode").value("1165010800"))
            .andExpect(jsonPath("$.data.regionName").value("서초동"))
            .andExpect(jsonPath("$.data.regionFullName").value("서울특별시 서초구 서초동"))
    }

    @Test
    fun `상품 수정 시 대표 이미지를 변경하면 DB의 thumbnailUrl도 변경된다`() {
        val member = memberRepository.save(Member.createUser("thumbnail-update@example.com", "encodedPassword", "대표변경판매자"))
        val category = categoryRepository.save(Category("대표이미지수정"))
        val product =
            Product.create(
                member,
                category,
                "아이폰 15",
                "상태 좋은 아이폰입니다.",
                BigDecimal.valueOf(800000),
                findRegion("1168010100"),
            )
        product.changeThumbnailUrl("https://example.com/old-1.jpg")
        val savedProduct = productRepository.saveAndFlush(product)
        productImageRepository.save(ProductImage.create(savedProduct, "https://example.com/old-1.jpg", 0, true))
        productImageRepository.saveAndFlush(ProductImage.create(savedProduct, "https://example.com/old-2.jpg", 1, false))
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "categoryId": ${category.id},
              "title": "아이폰 15",
              "description": "상태 좋은 아이폰입니다.",
              "price": 800000,
              "regionCode": "1168010100",
              "imageUrls": ["https://example.com/old-1.jpg", "https://example.com/old-2.jpg"],
              "thumbnailIndex": 1
            }
            """.trimIndent()

        mockMvc
            .perform(
                patch("/api/products/{productId}", savedProduct.id)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.thumbnailUrl").value("https://example.com/old-2.jpg"))
        productRepository.flush()
        entityManager.clear()

        val foundProduct = productRepository.findById(savedProduct.id!!).orElseThrow()
        assertThat(foundProduct.thumbnailUrl).isEqualTo("https://example.com/old-2.jpg")
    }

    @Test
    fun `인증 없이 상품 수정 요청 시 401을 반환한다`() {
        val body =
            """
            {
              "categoryId": 1,
              "title": "맥북 프로",
              "description": "수정된 상품 설명입니다.",
              "price": 1500000,
              "regionCode": "1165010800",
              "imageUrls": ["https://example.com/update-1.jpg"],
              "thumbnailIndex": 0
            }
            """.trimIndent()

        mockMvc
            .perform(
                patch("/api/products/{productId}", 1L)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
    }

    @Test
    fun `작성자가 아니면 상품 수정 시 PRODUCT_OWNER_ONLY를 반환한다`() {
        val owner = memberRepository.save(Member.createUser("owner@example.com", "encodedPassword", "작성자"))
        val other = memberRepository.save(Member.createUser("other@example.com", "encodedPassword", "다른사용자"))
        val category = categoryRepository.save(Category("테스트카테고리11"))
        val product =
            productRepository.saveAndFlush(
                Product.create(
                    owner,
                    category,
                    "아이폰 15",
                    "상태 좋은 아이폰입니다.",
                    BigDecimal.valueOf(800000),
                    findRegion("1168010100"),
                ),
            )
        val token = jwtTokenProvider.createAccessToken(other.id, other.role.name)
        val body =
            """
            {
              "categoryId": ${category.id},
              "title": "맥북 프로",
              "description": "수정된 상품 설명입니다.",
              "price": 1500000,
              "regionCode": "1165010800",
              "imageUrls": ["https://example.com/update-1.jpg"],
              "thumbnailIndex": 0
            }
            """.trimIndent()

        mockMvc
            .perform(
                patch("/api/products/{productId}", product.id)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("PRODUCT_OWNER_ONLY"))
    }

    @Test
    fun `거래완료 상품 수정 시 CANNOT_UPDATE_COMPLETED_PRODUCT를 반환한다`() {
        val member = memberRepository.save(Member.createUser("seller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리12"))
        val product =
            Product.create(member, category, "아이폰 15", "상태 좋은 아이폰입니다.", BigDecimal.valueOf(800000), findRegion("1168010100"))
        product.complete()
        val savedProduct = productRepository.saveAndFlush(product)
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "categoryId": ${category.id},
              "title": "맥북 프로",
              "description": "수정된 상품 설명입니다.",
              "price": 1500000,
              "regionCode": "1165010800",
              "imageUrls": ["https://example.com/update-1.jpg"],
              "thumbnailIndex": 0
            }
            """.trimIndent()

        mockMvc
            .perform(
                patch("/api/products/{productId}", savedProduct.id)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("CANNOT_UPDATE_COMPLETED_PRODUCT"))
    }

    @Test
    fun `작성자는 상품 거래 상태를 변경할 수 있다`() {
        val member = memberRepository.save(Member.createUser("seller-status@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리13"))
        val product =
            productRepository.saveAndFlush(
                Product.create(
                    member,
                    category,
                    "아이폰 15",
                    "상태 좋은 아이폰입니다.",
                    BigDecimal.valueOf(800000),
                    findRegion("1168010100"),
                ),
            )
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "tradeStatus": "RESERVED"
            }
            """.trimIndent()

        mockMvc
            .perform(
                patch("/api/products/{productId}/status", product.id)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))
            .andExpect(jsonPath("$.data.productId").value(product.id))
            .andExpect(jsonPath("$.data.tradeStatus").value("RESERVED"))
    }

    @Test
    fun `숨김 상품도 작성자라면 상품 거래 상태를 변경할 수 있다`() {
        val member = memberRepository.save(Member.createUser("hidden-status@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리14"))
        val product =
            Product.create(member, category, "숨김 상품", "숨김 상품 설명", BigDecimal.valueOf(30000), findRegion("1171010100"))
        product.hide()
        val savedProduct = productRepository.saveAndFlush(product)
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "tradeStatus": "RESERVED"
            }
            """.trimIndent()

        mockMvc
            .perform(
                patch("/api/products/{productId}/status", savedProduct.id)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tradeStatus").value("RESERVED"))
            .andExpect(jsonPath("$.data.hidden").value(true))
    }

    @Test
    fun `거래완료 상품에 거래완료 상태를 다시 요청하면 현재 상태를 그대로 반환한다`() {
        val member = memberRepository.save(Member.createUser("same-completed-status@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리15"))
        val product =
            Product.create(member, category, "아이폰 15", "상태 좋은 아이폰입니다.", BigDecimal.valueOf(800000), findRegion("1168010100"))
        product.complete()
        val savedProduct = productRepository.saveAndFlush(product)
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "tradeStatus": "COMPLETED"
            }
            """.trimIndent()

        mockMvc
            .perform(
                patch("/api/products/{productId}/status", savedProduct.id)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tradeStatus").value("COMPLETED"))
    }

    @Test
    fun `인증 없이 상품 거래 상태 변경 요청 시 401을 반환한다`() {
        val body =
            """
            {
              "tradeStatus": "RESERVED"
            }
            """.trimIndent()

        mockMvc
            .perform(
                patch("/api/products/{productId}/status", 1L)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
    }

    @Test
    fun `작성자가 아니면 상품 거래 상태 변경 시 PRODUCT_OWNER_ONLY를 반환한다`() {
        val owner = memberRepository.save(Member.createUser("owner-status@example.com", "encodedPassword", "작성자"))
        val other = memberRepository.save(Member.createUser("other-status@example.com", "encodedPassword", "다른사용자"))
        val category = categoryRepository.save(Category("테스트카테고리16"))
        val product =
            productRepository.saveAndFlush(
                Product.create(
                    owner,
                    category,
                    "아이폰 15",
                    "상태 좋은 아이폰입니다.",
                    BigDecimal.valueOf(800000),
                    findRegion("1168010100"),
                ),
            )
        val token = jwtTokenProvider.createAccessToken(other.id, other.role.name)
        val body =
            """
            {
              "tradeStatus": "RESERVED"
            }
            """.trimIndent()

        mockMvc
            .perform(
                patch("/api/products/{productId}/status", product.id)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("PRODUCT_OWNER_ONLY"))
    }

    @Test
    fun `거래완료 상품을 다른 거래 상태로 변경하면 CANNOT_CHANGE_COMPLETED_PRODUCT를 반환한다`() {
        val member = memberRepository.save(Member.createUser("completed-status@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리17"))
        val product =
            Product.create(member, category, "아이폰 15", "상태 좋은 아이폰입니다.", BigDecimal.valueOf(800000), findRegion("1168010100"))
        product.complete()
        val savedProduct = productRepository.saveAndFlush(product)
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "tradeStatus": "ON_SALE"
            }
            """.trimIndent()

        mockMvc
            .perform(
                patch("/api/products/{productId}/status", savedProduct.id)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("CANNOT_CHANGE_COMPLETED_PRODUCT"))
    }

    @Test
    fun `삭제된 상품 거래 상태 변경 시 DELETED_PRODUCT를 반환한다`() {
        val member = memberRepository.save(Member.createUser("deleted-status@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리18"))
        val product =
            Product.create(member, category, "삭제 상품", "삭제 상품 설명", BigDecimal.valueOf(40000), findRegion("1144012400"))
        product.softDelete()
        val savedProduct = productRepository.saveAndFlush(product)
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "tradeStatus": "RESERVED"
            }
            """.trimIndent()

        mockMvc
            .perform(
                patch("/api/products/{productId}/status", savedProduct.id)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("DELETED_PRODUCT"))
    }

    @Test
    fun `유효하지 않은 거래 상태로 변경하면 INVALID_TRADE_STATUS를 반환한다`() {
        val member = memberRepository.save(Member.createUser("invalid-status@example.com", "encodedPassword", "판매자"))
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "tradeStatus": "INVALID"
            }
            """.trimIndent()

        mockMvc
            .perform(
                patch("/api/products/{productId}/status", 1L)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_TRADE_STATUS"))
    }

    @Test
    fun `비어 있는 거래 상태로 변경하면 INVALID_TRADE_STATUS를 반환한다`() {
        val member = memberRepository.save(Member.createUser("blank-status@example.com", "encodedPassword", "판매자"))
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "tradeStatus": " "
            }
            """.trimIndent()

        mockMvc
            .perform(
                patch("/api/products/{productId}/status", 1L)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_TRADE_STATUS"))
    }

    @Test
    fun `null 거래 상태로 변경하면 INVALID_TRADE_STATUS를 반환한다`() {
        val member = memberRepository.save(Member.createUser("null-status@example.com", "encodedPassword", "판매자"))
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
              "tradeStatus": null
            }
            """.trimIndent()

        mockMvc
            .perform(
                patch("/api/products/{productId}/status", 1L)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_TRADE_STATUS"))
    }

    @Test
    fun `거래 상태 필드가 없으면 INVALID_TRADE_STATUS를 반환한다`() {
        val member = memberRepository.save(Member.createUser("missing-status@example.com", "encodedPassword", "판매자"))
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)
        val body =
            """
            {
            }
            """.trimIndent()

        mockMvc
            .perform(
                patch("/api/products/{productId}/status", 1L)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_TRADE_STATUS"))
    }

    @Test
    fun `작성자는 상품을 삭제할 수 있다`() {
        val member = memberRepository.save(Member.createUser("seller-delete@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("테스트카테고리19"))
        val product =
            productRepository.saveAndFlush(
                Product.create(
                    member,
                    category,
                    "아이폰 15",
                    "상태 좋은 아이폰입니다.",
                    BigDecimal.valueOf(800000),
                    findRegion("1168010100"),
                ),
            )
        val token = jwtTokenProvider.createAccessToken(member.id, member.role.name)

        mockMvc
            .perform(
                delete("/api/products/{productId}", product.id)
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(200))

        mockMvc
            .perform(get("/api/products/{productId}", product.id))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("DELETED_PRODUCT"))
    }

    @Test
    fun `인증 없이 상품 삭제 요청 시 401을 반환한다`() {
        mockMvc
            .perform(delete("/api/products/{productId}", 1L))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
    }

    @Test
    fun `작성자가 아니면 상품 삭제 시 PRODUCT_OWNER_ONLY를 반환한다`() {
        val owner = memberRepository.save(Member.createUser("owner-delete@example.com", "encodedPassword", "작성자"))
        val other = memberRepository.save(Member.createUser("other-delete@example.com", "encodedPassword", "다른사용자"))
        val category = categoryRepository.save(Category("테스트카테고리20"))
        val product =
            productRepository.saveAndFlush(
                Product.create(
                    owner,
                    category,
                    "아이폰 15",
                    "상태 좋은 아이폰입니다.",
                    BigDecimal.valueOf(800000),
                    findRegion("1168010100"),
                ),
            )
        val token = jwtTokenProvider.createAccessToken(other.id, other.role.name)

        mockMvc
            .perform(
                delete("/api/products/{productId}", product.id)
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("PRODUCT_OWNER_ONLY"))
    }

    private fun findRegion(code: String): Region = regionRepository.findByCode(code).orElseThrow()
}
