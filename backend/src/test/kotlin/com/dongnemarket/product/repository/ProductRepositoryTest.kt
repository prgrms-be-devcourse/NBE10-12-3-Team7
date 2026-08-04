package com.dongnemarket.product.repository

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.global.config.JpaAuditingConfig
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.TradeStatus
import com.dongnemarket.product.repository.spec.ProductSpecification
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.hibernate.SessionFactory
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Sort
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal

/**
 * [통합] 상품 리포지토리 — 기본 저장/조회, favoriteCount 원자 UPDATE, ProductSpecification 조건 조합.
 * 목록·검색 조건은 숨김/삭제/거래완료 상품과 탈퇴·정지 판매자 상품을 모두 걸러야 하므로
 * 조건별로 제외 대상 상품을 함께 저장해 실제 SQL 이 걸러내는지 확인한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig::class)
class ProductRepositoryTest {
    @Autowired
    lateinit var productRepository: ProductRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var categoryRepository: CategoryRepository

    @Autowired
    lateinit var regionRepository: RegionRepository

    @Autowired
    lateinit var entityManager: EntityManager

    @Test
    fun `상품을 저장하고 기본 필드와 시간 필드를 조회할 수 있다`() {
        val member = memberRepository.save(Member.createUser("seller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("디지털기기"))

        val product =
            Product.create(
                member,
                category,
                "아이폰 15",
                "상태 좋은 아이폰입니다.",
                BigDecimal.valueOf(800000),
                saveGangnamWithDong(),
            )

        val savedProduct = productRepository.saveAndFlush(product)

        assertThat(savedProduct.id).isNotNull()
        assertThat(savedProduct.member.id).isEqualTo(member.id)
        assertThat(savedProduct.category.id).isEqualTo(category.id)
        assertThat(savedProduct.title).isEqualTo("아이폰 15")
        assertThat(savedProduct.description).isEqualTo("상태 좋은 아이폰입니다.")
        assertThat(savedProduct.price).isEqualByComparingTo("800000")
        assertThat(savedProduct.tradeStatus).isEqualTo(TradeStatus.ON_SALE)
        assertThat(savedProduct.regionCode).isEqualTo("1168010100")
        assertThat(savedProduct.regionFullName).isEqualTo("서울특별시 강남구 역삼동")
        assertThat(savedProduct.viewCount).isZero()
        assertThat(savedProduct.isHidden).isFalse()
        assertThat(savedProduct.deletedAt).isNull()
        assertThat(savedProduct.createdAt).isNotNull()
        assertThat(savedProduct.updatedAt).isNotNull()
    }

    @Test
    fun `삭제되지 않고 숨김 처리되지 않은 상품이면 접근 가능한 상품으로 판단한다`() {
        val member = memberRepository.save(Member.createUser("seller-accessible@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("생활가전"))
        val product =
            productRepository.saveAndFlush(
                Product.create(
                    member,
                    category,
                    "공기청정기",
                    "상태 좋은 공기청정기입니다.",
                    BigDecimal.valueOf(120000),
                    saveGangnamWithDong(),
                ),
            )

        val exists = productRepository.existsByIdAndDeletedAtIsNullAndIsHiddenFalse(product.id!!)

        assertThat(exists).isTrue()
    }

    @Test
    fun `상품 저장 시 favoriteCount 기본값은 0이다`() {
        val member = memberRepository.save(Member.createUser("favorite-default@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("디지털기기"))
        val product =
            productRepository.saveAndFlush(
                Product.create(
                    member,
                    category,
                    "아이패드",
                    "깨끗한 아이패드입니다.",
                    BigDecimal.valueOf(500000),
                    saveGangnamWithDong(),
                ),
            )

        assertThat(product.favoriteCount).isZero()
    }

    @Test
    fun `favoriteCount는 원자 UPDATE로 1 증가한다`() {
        val member = memberRepository.save(Member.createUser("favorite-increment@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("생활가전"))
        val product =
            productRepository.saveAndFlush(
                Product.create(
                    member,
                    category,
                    "청소기",
                    "상태 좋은 청소기입니다.",
                    BigDecimal.valueOf(150000),
                    saveSeochoWithDong(),
                ),
            )

        productRepository.incrementFavoriteCount(product.id!!)
        productRepository.flush()
        entityManager.clear()

        val foundProduct = productRepository.findById(product.id!!).orElseThrow()
        assertThat(foundProduct.favoriteCount).isEqualTo(1)
    }

    @Test
    fun `favoriteCount는 원자 UPDATE로 1 감소한다`() {
        val member = memberRepository.save(Member.createUser("favorite-decrement@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("가구/인테리어"))
        val product =
            productRepository.saveAndFlush(
                Product.create(
                    member,
                    category,
                    "책상",
                    "튼튼한 책상입니다.",
                    BigDecimal.valueOf(70000),
                    saveSongpaWithDong(),
                ),
            )
        productRepository.incrementFavoriteCount(product.id!!)
        productRepository.flush()
        entityManager.clear()

        productRepository.decrementFavoriteCount(product.id!!)
        productRepository.flush()
        entityManager.clear()

        val foundProduct = productRepository.findById(product.id!!).orElseThrow()
        assertThat(foundProduct.favoriteCount).isZero()
    }

    @Test
    fun `favoriteCount가 0이면 감소 요청을 해도 음수가 되지 않는다`() {
        val member = memberRepository.save(Member.createUser("favorite-zero@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("도서"))
        val product =
            productRepository.saveAndFlush(
                Product.create(
                    member,
                    category,
                    "자바 책",
                    "깨끗한 자바 책입니다.",
                    BigDecimal.valueOf(20000),
                    saveMapoWithDong(),
                ),
            )

        productRepository.decrementFavoriteCount(product.id!!)
        productRepository.flush()
        entityManager.clear()

        val foundProduct = productRepository.findById(product.id!!).orElseThrow()
        assertThat(foundProduct.favoriteCount).isZero()
    }

    @Nested
    @DisplayName("관심 수 원자 업데이트")
    inner class FavoriteCountAtomicUpdate {
        @Test
        fun `존재하지 않는 상품의 관심 수 증가를 요청해도 예외가 발생하지 않는다`() {
            val missingProductId = Long.MAX_VALUE

            assertThatCode { productRepository.incrementFavoriteCount(missingProductId) }
                .doesNotThrowAnyException()
        }

        @Test
        fun `존재하지 않는 상품의 관심 수 감소를 요청해도 예외가 발생하지 않는다`() {
            val missingProductId = Long.MAX_VALUE

            assertThatCode { productRepository.decrementFavoriteCount(missingProductId) }
                .doesNotThrowAnyException()
        }

        @Test
        fun `특정 상품의 관심 수만 증가하고 다른 상품은 변경되지 않는다`() {
            val member = memberRepository.save(Member.createUser("favorite-target@example.com", "encodedPassword", "판매자"))
            val category = categoryRepository.save(Category("반려동물용품"))
            val targetProduct = saveFavoriteCountProduct(member, category, "관심 증가 대상 상품")
            val otherProduct = saveFavoriteCountProduct(member, category, "관심 증가 비대상 상품")

            productRepository.incrementFavoriteCount(targetProduct.id!!)
            productRepository.flush()
            entityManager.clear()

            val foundTargetProduct = productRepository.findById(targetProduct.id!!).orElseThrow()
            val foundOtherProduct = productRepository.findById(otherProduct.id!!).orElseThrow()
            assertThat(foundTargetProduct.favoriteCount).isEqualTo(1)
            assertThat(foundOtherProduct.favoriteCount).isZero()
        }

        @Test
        fun `관심 수 증가와 감소를 여러 번 호출하면 최종 값이 정확히 반영된다`() {
            val member = memberRepository.save(Member.createUser("favorite-repeated@example.com", "encodedPassword", "판매자"))
            val category = categoryRepository.save(Category("기타"))
            val product = saveFavoriteCountProduct(member, category, "관심 반복 변경 상품")

            productRepository.incrementFavoriteCount(product.id!!)
            productRepository.incrementFavoriteCount(product.id!!)
            productRepository.incrementFavoriteCount(product.id!!)
            productRepository.decrementFavoriteCount(product.id!!)
            productRepository.flush()
            entityManager.clear()

            val foundProduct = productRepository.findById(product.id!!).orElseThrow()
            assertThat(foundProduct.favoriteCount).isEqualTo(2)
        }

        private fun saveFavoriteCountProduct(
            member: Member,
            category: Category,
            title: String,
        ): Product =
            productRepository.saveAndFlush(
                Product.create(
                    member,
                    category,
                    title,
                    "관심 수 원자 업데이트 테스트 상품입니다.",
                    BigDecimal.valueOf(10000),
                    saveGangnamWithDong(),
                ),
            )
    }

    @Test
    fun `삭제된 상품이면 접근 가능한 상품으로 판단하지 않는다`() {
        val member = memberRepository.save(Member.createUser("seller-deleted@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("가구/인테리어"))
        val product =
            Product.create(
                member,
                category,
                "의자",
                "사용감 있는 의자입니다.",
                BigDecimal.valueOf(30000),
                saveSeochoWithDong(),
            )
        product.softDelete()
        val savedProduct = productRepository.saveAndFlush(product)

        val exists = productRepository.existsByIdAndDeletedAtIsNullAndIsHiddenFalse(savedProduct.id!!)

        assertThat(exists).isFalse()
    }

    @Test
    fun `숨김 상품이면 접근 가능한 상품으로 판단하지 않는다`() {
        val member = memberRepository.save(Member.createUser("seller-hidden@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("도서"))
        val product =
            Product.create(
                member,
                category,
                "자바 책",
                "깨끗한 자바 책입니다.",
                BigDecimal.valueOf(15000),
                saveSongpaWithDong(),
            )
        product.hide()
        val savedProduct = productRepository.saveAndFlush(product)

        val exists = productRepository.existsByIdAndDeletedAtIsNullAndIsHiddenFalse(savedProduct.id!!)

        assertThat(exists).isFalse()
    }

    @Test
    fun `카테고리별 상품은 최신 등록순으로 조회하고 숨김·삭제 상품은 제외한다`() {
        val member = memberRepository.save(Member.createUser("category-seller@example.com", "encodedPassword", "판매자"))
        val targetCategory = categoryRepository.save(Category("생활가전"))
        val otherCategory = categoryRepository.save(Category("도서"))
        val oldProduct =
            productRepository.save(
                Product.create(
                    member,
                    targetCategory,
                    "오래된 생활가전",
                    "오래된 생활가전 설명",
                    BigDecimal.valueOf(10000),
                    saveGangnamWithDong(),
                ),
            )
        val newProduct =
            productRepository.save(
                Product.create(
                    member,
                    targetCategory,
                    "최신 생활가전",
                    "최신 생활가전 설명",
                    BigDecimal.valueOf(20000),
                    saveSeochoWithDong(),
                ),
            )
        productRepository.save(
            Product.create(
                member,
                otherCategory,
                "다른 카테고리 상품",
                "다른 카테고리 상품 설명",
                BigDecimal.valueOf(30000),
                saveSongpaWithDong(),
            ),
        )
        val hiddenProduct =
            Product.create(member, targetCategory, "숨김 상품", "숨김 상품 설명", BigDecimal.valueOf(40000), saveMapoWithDong())
        hiddenProduct.hide()
        productRepository.save(hiddenProduct)
        val deletedProduct =
            Product.create(member, targetCategory, "삭제 상품", "삭제 상품 설명", BigDecimal.valueOf(50000), saveYongsanWithDong())
        deletedProduct.softDelete()
        productRepository.saveAndFlush(deletedProduct)

        val products =
            productRepository.findAll(
                ProductSpecification.categoryList(targetCategory.id),
                Sort.by(Sort.Direction.DESC, "id"),
            )

        assertThat(products).containsExactly(newProduct, oldProduct)
    }

    @Test
    fun `내 상품 목록은 최신 등록순으로 조회하고 숨김 상품은 포함하며 삭제 상품은 제외한다`() {
        val member = memberRepository.save(Member.createUser("my-seller@example.com", "encodedPassword", "판매자"))
        val otherMember = memberRepository.save(Member.createUser("other-seller@example.com", "encodedPassword", "다른판매자"))
        val category = categoryRepository.save(Category("스포츠/레저"))
        val oldProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "오래된 내 상품",
                    "오래된 내 상품 설명",
                    BigDecimal.valueOf(10000),
                    saveGangnamWithDong(),
                ),
            )
        val hiddenProduct =
            Product.create(member, category, "숨김 내 상품", "숨김 내 상품 설명", BigDecimal.valueOf(20000), saveSeochoWithDong())
        hiddenProduct.hide()
        val savedHiddenProduct = productRepository.save(hiddenProduct)
        productRepository.save(
            Product.create(
                otherMember,
                category,
                "다른 회원 상품",
                "다른 회원 상품 설명",
                BigDecimal.valueOf(30000),
                saveSongpaWithDong(),
            ),
        )
        val deletedProduct =
            Product.create(member, category, "삭제 내 상품", "삭제 내 상품 설명", BigDecimal.valueOf(40000), saveMapoWithDong())
        deletedProduct.softDelete()
        productRepository.saveAndFlush(deletedProduct)

        val products = productRepository.findAllByMemberIdAndDeletedAtIsNullOrderByIdDesc(member.id)

        assertThat(products).containsExactly(savedHiddenProduct, oldProduct)
    }

    @Test
    fun `상품 검색은 키워드로 제목과 설명을 검색하고 숨김·삭제 상품은 제외한다`() {
        val member = memberRepository.save(Member.createUser("search-seller@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("디지털기기"))
        val titleMatchedProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "맥북 프로",
                    "상태 좋은 노트북입니다.",
                    BigDecimal.valueOf(1200000),
                    saveGangnamWithDong(),
                ),
            )
        val descriptionMatchedProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "노트북 거치대",
                    "맥북과 함께 쓰기 좋습니다.",
                    BigDecimal.valueOf(30000),
                    saveSeochoWithDong(),
                ),
            )
        val hiddenProduct =
            Product.create(member, category, "숨김 맥북", "숨김 상품입니다.", BigDecimal.valueOf(900000), saveSongpaWithDong())
        hiddenProduct.hide()
        productRepository.save(hiddenProduct)
        val deletedProduct =
            Product.create(member, category, "삭제 맥북", "삭제 상품입니다.", BigDecimal.valueOf(800000), saveMapoWithDong())
        deletedProduct.softDelete()
        productRepository.saveAndFlush(deletedProduct)

        val products =
            productRepository.findAll(
                ProductSpecification.search("맥북", null, null, null, null, null),
                Sort.by(Sort.Direction.DESC, "id"),
            )

        assertThat(products).containsExactly(descriptionMatchedProduct, titleMatchedProduct)
    }

    @Test
    fun `상품 검색은 카테고리, 가격 범위, 거래 상태를 함께 필터링한다`() {
        val member = memberRepository.save(Member.createUser("filter-seller@example.com", "encodedPassword", "판매자"))
        val targetCategory = categoryRepository.save(Category("생활가전"))
        val otherCategory = categoryRepository.save(Category("도서"))
        val targetProduct =
            Product.create(
                member,
                targetCategory,
                "예약 중인 청소기",
                "상태 좋은 청소기입니다.",
                BigDecimal.valueOf(150000),
                saveGangnamWithDong(),
            )
        targetProduct.changeTradeStatus(TradeStatus.RESERVED)
        val savedTargetProduct = productRepository.save(targetProduct)
        val wrongStatusProduct =
            productRepository.save(
                Product.create(
                    member,
                    targetCategory,
                    "판매 중인 청소기",
                    "상태 좋은 청소기입니다.",
                    BigDecimal.valueOf(160000),
                    saveSeochoWithDong(),
                ),
            )
        val wrongCategoryProduct =
            Product.create(
                member,
                otherCategory,
                "예약 중인 책",
                "청소기 설명이 있는 책입니다.",
                BigDecimal.valueOf(150000),
                saveSongpaWithDong(),
            )
        wrongCategoryProduct.changeTradeStatus(TradeStatus.RESERVED)
        productRepository.save(wrongCategoryProduct)
        val wrongPriceProduct =
            Product.create(member, targetCategory, "비싼 청소기", "비싼 청소기입니다.", BigDecimal.valueOf(500000), saveMapoWithDong())
        wrongPriceProduct.changeTradeStatus(TradeStatus.RESERVED)
        productRepository.saveAndFlush(wrongPriceProduct)

        val products =
            productRepository.findAll(
                ProductSpecification.search(
                    "청소기",
                    targetCategory.id,
                    BigDecimal.valueOf(100000),
                    BigDecimal.valueOf(200000),
                    TradeStatus.RESERVED,
                    null,
                ),
                Sort.by(Sort.Direction.DESC, "id"),
            )

        assertThat(products).containsExactly(savedTargetProduct)
        assertThat(products).doesNotContain(wrongStatusProduct, wrongCategoryProduct, wrongPriceProduct)
    }

    @Test
    fun `상품 목록 조건은 지역 필터가 없어도 기존처럼 숨김·삭제 상품을 제외하고 최신순으로 조회한다`() {
        val member = memberRepository.save(Member.createUser("region-list-regression@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("지역목록회귀"))
        val oldProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "오래된 상품",
                    "오래된 상품 설명",
                    BigDecimal.valueOf(10000),
                    saveGangnamWithDong(),
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
                    saveMapoWithDong(),
                ),
            )
        val hiddenProduct =
            Product.create(member, category, "숨김 상품", "숨김 상품 설명", BigDecimal.valueOf(30000), saveSeochoWithDong())
        hiddenProduct.hide()
        productRepository.save(hiddenProduct)
        val deletedProduct =
            Product.create(member, category, "삭제 상품", "삭제 상품 설명", BigDecimal.valueOf(40000), saveSongpaWithDong())
        deletedProduct.softDelete()
        productRepository.saveAndFlush(deletedProduct)

        val products =
            productRepository.findAll(
                ProductSpecification.list(null, null),
                Sort.by(Sort.Direction.DESC, "id"),
            )

        assertThat(products).containsExactly(newProduct, oldProduct)
        assertThat(products).doesNotContain(hiddenProduct, deletedProduct)
    }

    @Test
    fun `상품 목록 조건은 탈퇴·정지 판매자 상품과 거래완료 상품을 제외한다`() {
        val activeMember = memberRepository.save(Member.createUser("visible-active@example.com", "encodedPassword", "활성판매자"))
        val deletedMember = memberRepository.save(Member.createUser("visible-deleted@example.com", "encodedPassword", "탈퇴판매자"))
        deletedMember.changeStatus(MemberStatus.DELETED)
        val suspendedMember = memberRepository.save(Member.createUser("visible-suspended@example.com", "encodedPassword", "정지판매자"))
        suspendedMember.changeStatus(MemberStatus.SUSPENDED)
        val category = categoryRepository.save(Category("공개목록정책"))
        val onSaleProduct =
            productRepository.save(
                Product.create(
                    activeMember,
                    category,
                    "판매중 공개 상품",
                    "판매중 공개 상품 설명",
                    BigDecimal.valueOf(10000),
                    saveGangnamWithDong(),
                ),
            )
        val reservedProduct =
            Product.create(activeMember, category, "예약중 공개 상품", "예약중 공개 상품 설명", BigDecimal.valueOf(20000), saveMapoWithDong())
        reservedProduct.changeTradeStatus(TradeStatus.RESERVED)
        val savedReservedProduct = productRepository.save(reservedProduct)
        val completedProduct =
            Product.create(activeMember, category, "거래완료 상품", "거래완료 상품 설명", BigDecimal.valueOf(30000), saveSeochoWithDong())
        completedProduct.complete()
        productRepository.save(completedProduct)
        val deletedSellerProduct =
            productRepository.save(
                Product.create(
                    deletedMember,
                    category,
                    "탈퇴 판매자 상품",
                    "탈퇴 판매자 상품 설명",
                    BigDecimal.valueOf(40000),
                    saveSongpaWithDong(),
                ),
            )
        val suspendedSellerProduct =
            productRepository.saveAndFlush(
                Product.create(
                    suspendedMember,
                    category,
                    "정지 판매자 상품",
                    "정지 판매자 상품 설명",
                    BigDecimal.valueOf(50000),
                    saveYongsanWithDong(),
                ),
            )

        val products =
            productRepository.findAll(
                ProductSpecification.list(null, null),
                Sort.by(Sort.Direction.DESC, "id"),
            )

        assertThat(products).containsExactly(savedReservedProduct, onSaleProduct)
        assertThat(products).doesNotContain(completedProduct, deletedSellerProduct, suspendedSellerProduct)
    }

    @Test
    fun `상품 목록 조건은 시군구 regionCode를 지정하면 하위 동 상품을 조회한다`() {
        val member = memberRepository.save(Member.createUser("region-one@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("지역필터1"))
        val gangnam = saveGangnam()
        val yeoksam = saveDong(gangnam, "1168010100", "서울특별시 강남구 역삼동", "역삼동")
        val daechi = saveDong(gangnam, "1168010600", "서울특별시 강남구 대치동", "대치동")
        val songpa = saveSongpaWithDong()
        val gangnamProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "역삼 상품",
                    "역삼 상품 설명",
                    BigDecimal.valueOf(10000),
                    yeoksam,
                ),
            )
        val daechiProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "대치 상품",
                    "대치 상품 설명",
                    BigDecimal.valueOf(20000),
                    daechi,
                ),
            )
        val songpaProduct =
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

        val products =
            productRepository.findAll(
                ProductSpecification.list(listOf("1168000000"), null),
                Sort.by(Sort.Direction.DESC, "id"),
            )

        assertThat(products).containsExactly(daechiProduct, gangnamProduct)
        assertThat(products).doesNotContain(songpaProduct)
    }

    /**
     * `ProductSpecification.toRegionCodePrefix` 는 코드가 "00000000" 으로 끝나면 앞 2자리,
     * "00000" 으로 끝나면 앞 5자리로 자른다. 위 테스트가 5자리(시군구) 분기를 덮고,
     * 이 테스트가 2자리(시도) 분기를 덮는다. 2자리 분기가 깨지면 예외가 아니라 **빈 목록**이
     * 나오므로 다른 시도 상품이 섞이지 않는지까지 함께 확인한다.
     */
    @Test
    fun `상품 목록 조건은 시도 regionCode를 지정하면 산하 모든 시군구 상품을 조회한다`() {
        val member = memberRepository.save(Member.createUser("region-sido@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("지역필터시도"))
        val yeoksam = saveGangnamWithDong()
        val jamsil = saveSongpaWithDong()
        val busanDong = saveBusanWithDong()
        val gangnamProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "강남 상품",
                    "강남 상품 설명",
                    BigDecimal.valueOf(10000),
                    yeoksam,
                ),
            )
        val songpaProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "송파 상품",
                    "송파 상품 설명",
                    BigDecimal.valueOf(20000),
                    jamsil,
                ),
            )
        val busanProduct =
            productRepository.saveAndFlush(
                Product.create(
                    member,
                    category,
                    "부산 상품",
                    "부산 상품 설명",
                    BigDecimal.valueOf(30000),
                    busanDong,
                ),
            )

        val products =
            productRepository.findAll(
                ProductSpecification.list(listOf("1100000000"), null),
                Sort.by(Sort.Direction.DESC, "id"),
            )

        // 서울(11) 산하 두 개 시군구 상품이 시군구를 가리지 않고 모두 잡힌다.
        assertThat(products).containsExactly(songpaProduct, gangnamProduct)
        assertThat(products).doesNotContain(busanProduct)
    }

    @Test
    fun `상품 목록 조건은 regionCode 2개를 지정하면 두 지역 상품을 최신순으로 조회한다`() {
        val member = memberRepository.save(Member.createUser("region-two@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("지역필터2"))
        val gangnam = saveGangnam()
        val yeoksam = saveDong(gangnam, "1168010100", "서울특별시 강남구 역삼동", "역삼동")
        val mapo = saveMapoWithDong()
        val songpa = saveSongpaWithDong()
        val gangnamProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "강남 상품",
                    "강남 상품 설명",
                    BigDecimal.valueOf(10000),
                    yeoksam,
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
        val otherProduct =
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

        val products =
            productRepository.findAll(
                ProductSpecification.list(listOf("1168000000", "1144000000"), null),
                Sort.by(Sort.Direction.DESC, "id"),
            )

        assertThat(products).containsExactly(mapoProduct, gangnamProduct)
        assertThat(products).doesNotContain(otherProduct)
    }

    @Test
    fun `상품 목록 조건은 존재하지 않는 지역을 지정하면 빈 목록을 반환한다`() {
        val member = memberRepository.save(Member.createUser("region-unknown@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("지역필터없음"))
        val gangnam = saveGangnam()
        val yeoksam = saveDong(gangnam, "1168010100", "서울특별시 강남구 역삼동", "역삼동")
        productRepository.saveAndFlush(
            Product.create(
                member,
                category,
                "강남 상품",
                "강남 상품 설명",
                BigDecimal.valueOf(10000),
                yeoksam,
            ),
        )

        val products =
            productRepository.findAll(
                ProductSpecification.list(listOf("9999999999"), null),
                Sort.by(Sort.Direction.DESC, "id"),
            )

        assertThat(products).isEmpty()
    }

    @Test
    fun `상품 목록 조건은 커서가 있으면 커서보다 작은 id의 상품만 조회한다`() {
        val member = memberRepository.save(Member.createUser("cursor-repository@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("커서필터"))
        val oldProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "오래된 상품",
                    "오래된 상품 설명",
                    BigDecimal.valueOf(10000),
                    saveGangnamWithDong(),
                ),
            )
        val cursorProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "커서 상품",
                    "커서 상품 설명",
                    BigDecimal.valueOf(20000),
                    saveGangnamWithDong(),
                ),
            )
        val newProduct =
            productRepository.saveAndFlush(
                Product.create(
                    member,
                    category,
                    "최신 상품",
                    "최신 상품 설명",
                    BigDecimal.valueOf(30000),
                    saveGangnamWithDong(),
                ),
            )

        val products =
            productRepository.findAll(
                ProductSpecification.list(null, cursorProduct.id),
                Sort.by(Sort.Direction.DESC, "id"),
            )

        assertThat(products).containsExactly(oldProduct)
        assertThat(products).doesNotContain(cursorProduct, newProduct)
    }

    @Test
    fun `상품 목록 조건은 지역 필터와 커서 조건을 함께 적용한다`() {
        val member = memberRepository.save(Member.createUser("cursor-region-repository@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("커서지역필터"))
        val gangnam = saveGangnam()
        val yeoksam = saveDong(gangnam, "1168010100", "서울특별시 강남구 역삼동", "역삼동")
        val mapo = saveMapoWithDong()
        val gangnamOldProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "강남 오래된 상품",
                    "강남 오래된 상품 설명",
                    BigDecimal.valueOf(10000),
                    yeoksam,
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
        val gangnamCursorProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "강남 커서 상품",
                    "강남 커서 상품 설명",
                    BigDecimal.valueOf(30000),
                    yeoksam,
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
                    yeoksam,
                ),
            )

        val products =
            productRepository.findAll(
                ProductSpecification.list(listOf("1168000000"), gangnamCursorProduct.id),
                Sort.by(Sort.Direction.DESC, "id"),
            )

        assertThat(products).containsExactly(gangnamOldProduct)
        assertThat(products).doesNotContain(mapoProduct, gangnamCursorProduct, gangnamNewProduct)
    }

    @Test
    fun `상품 검색 조건은 키워드와 지역 필터를 함께 적용한다`() {
        val member = memberRepository.save(Member.createUser("region-search@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("지역검색"))
        val gangnam = saveGangnam()
        val yeoksam = saveDong(gangnam, "1168010100", "서울특별시 강남구 역삼동", "역삼동")
        val songpa = saveSongpaWithDong()
        val matchedProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "맥북 프로",
                    "상태 좋은 노트북입니다.",
                    BigDecimal.valueOf(1200000),
                    yeoksam,
                ),
            )
        val wrongRegionProduct =
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "맥북 에어",
                    "가벼운 노트북입니다.",
                    BigDecimal.valueOf(900000),
                    songpa,
                ),
            )
        val wrongKeywordProduct =
            productRepository.saveAndFlush(
                Product.create(
                    member,
                    category,
                    "아이패드",
                    "상태 좋은 태블릿입니다.",
                    BigDecimal.valueOf(700000),
                    yeoksam,
                ),
            )

        val products =
            productRepository.findAll(
                ProductSpecification.search("맥북", null, null, null, null, listOf("1168000000")),
                Sort.by(Sort.Direction.DESC, "id"),
            )

        assertThat(products).containsExactly(matchedProduct)
        assertThat(products).doesNotContain(wrongRegionProduct, wrongKeywordProduct)
    }

    @Test
    fun `상품 검색 조건은 탈퇴·정지 판매자 상품과 거래완료 상품을 제외한다`() {
        val activeMember =
            memberRepository.save(Member.createUser("search-visible-active@example.com", "encodedPassword", "활성검색판매자"))
        val deletedMember =
            memberRepository.save(Member.createUser("search-visible-deleted@example.com", "encodedPassword", "탈퇴검색판매자"))
        deletedMember.changeStatus(MemberStatus.DELETED)
        val suspendedMember =
            memberRepository.save(Member.createUser("search-visible-suspended@example.com", "encodedPassword", "정지검색판매자"))
        suspendedMember.changeStatus(MemberStatus.SUSPENDED)
        val category = categoryRepository.save(Category("공개검색정책"))
        val gangnam = saveGangnam()
        val yeoksam = saveDong(gangnam, "1168010100", "서울특별시 강남구 역삼동", "역삼동")
        val visibleProduct =
            productRepository.save(
                Product.create(
                    activeMember,
                    category,
                    "정책 맥북",
                    "공개 검색 상품입니다.",
                    BigDecimal.valueOf(1000000),
                    yeoksam,
                ),
            )
        val completedProduct =
            Product.create(activeMember, category, "정책 완료 맥북", "거래완료 검색 상품입니다.", BigDecimal.valueOf(900000), yeoksam)
        completedProduct.complete()
        productRepository.save(completedProduct)
        val deletedSellerProduct =
            productRepository.save(
                Product.create(
                    deletedMember,
                    category,
                    "정책 탈퇴 맥북",
                    "탈퇴 판매자 검색 상품입니다.",
                    BigDecimal.valueOf(800000),
                    yeoksam,
                ),
            )
        val suspendedSellerProduct =
            productRepository.saveAndFlush(
                Product.create(
                    suspendedMember,
                    category,
                    "정책 정지 맥북",
                    "정지 판매자 검색 상품입니다.",
                    BigDecimal.valueOf(700000),
                    yeoksam,
                ),
            )

        val products =
            productRepository.findAll(
                ProductSpecification.search("정책", null, null, null, null, listOf("1168000000")),
                Sort.by(Sort.Direction.DESC, "id"),
            )

        assertThat(products).containsExactly(visibleProduct)
        assertThat(products).doesNotContain(completedProduct, deletedSellerProduct, suspendedSellerProduct)
    }

    @Test
    fun `상품 검색 조건은 거래완료 상태를 요청하면 빈 목록을 반환한다`() {
        val member = memberRepository.save(Member.createUser("search-completed-empty@example.com", "encodedPassword", "완료검색판매자"))
        val category = categoryRepository.save(Category("거래완료검색"))
        val completedProduct =
            Product.create(
                member,
                category,
                "거래완료 맥북",
                "거래완료 검색 상품입니다.",
                BigDecimal.valueOf(1000000),
                saveGangnamWithDong(),
            )
        completedProduct.complete()
        productRepository.saveAndFlush(completedProduct)

        val products =
            productRepository.findAll(
                ProductSpecification.search("맥북", null, null, null, TradeStatus.COMPLETED, null),
                Sort.by(Sort.Direction.DESC, "id"),
            )

        assertThat(products).isEmpty()
    }

    @Test
    fun `카테고리별 상품 조건은 탈퇴·정지 판매자 상품과 거래완료 상품을 제외한다`() {
        val activeMember =
            memberRepository.save(Member.createUser("category-visible-active@example.com", "encodedPassword", "활성카테고리판매자"))
        val deletedMember =
            memberRepository.save(Member.createUser("category-visible-deleted@example.com", "encodedPassword", "탈퇴카테고리판매자"))
        deletedMember.changeStatus(MemberStatus.DELETED)
        val suspendedMember =
            memberRepository.save(Member.createUser("category-visible-suspended@example.com", "encodedPassword", "정지카테고리판매자"))
        suspendedMember.changeStatus(MemberStatus.SUSPENDED)
        val category = categoryRepository.save(Category("공개카테고리정책"))
        val visibleProduct =
            productRepository.save(
                Product.create(
                    activeMember,
                    category,
                    "카테고리 공개 상품",
                    "카테고리 공개 상품 설명",
                    BigDecimal.valueOf(10000),
                    saveGangnamWithDong(),
                ),
            )
        val completedProduct =
            Product.create(
                activeMember,
                category,
                "카테고리 거래완료 상품",
                "카테고리 거래완료 상품 설명",
                BigDecimal.valueOf(20000),
                saveGangnamWithDong(),
            )
        completedProduct.complete()
        productRepository.save(completedProduct)
        val deletedSellerProduct =
            productRepository.save(
                Product.create(
                    deletedMember,
                    category,
                    "카테고리 탈퇴 판매자 상품",
                    "카테고리 탈퇴 판매자 상품 설명",
                    BigDecimal.valueOf(30000),
                    saveGangnamWithDong(),
                ),
            )
        val suspendedSellerProduct =
            productRepository.saveAndFlush(
                Product.create(
                    suspendedMember,
                    category,
                    "카테고리 정지 판매자 상품",
                    "카테고리 정지 판매자 상품 설명",
                    BigDecimal.valueOf(40000),
                    saveGangnamWithDong(),
                ),
            )

        val products =
            productRepository.findAll(
                ProductSpecification.categoryList(category.id),
                Sort.by(Sort.Direction.DESC, "id"),
            )

        assertThat(products).containsExactly(visibleProduct)
        assertThat(products).doesNotContain(completedProduct, deletedSellerProduct, suspendedSellerProduct)
    }

    /**
     * 목록 조회 후 지역 필드를 읽을 때 상품 수만큼 SELECT 가 따라붙지 않는지 확인한다.
     *
     * `Product.regionRef` 는 `LAZY` 라 [com.dongnemarket.product.dto.ProductSummaryResponse] 가
     * `regionCode`·`regionName`·`regionFullName` 을 읽는 순간 프록시가 깨진다. 지역이 상품마다
     * 다르면 그만큼 SELECT 가 늘어난다(N+1). `Region` 의 `@BatchSize` 가 대기 중인 프록시를 모아
     * `where id in (...)` 한 번으로 가져오므로, 상품 수와 무관하게 쿼리 수가 일정해야 한다.
     *
     * `@BatchSize` 를 지우면 목록 1 + 지역 5 = 6 건이 되어 이 테스트가 깨진다.
     */
    @Test
    fun `목록 조회 후 지역 필드를 읽어도 상품 수만큼 추가 조회가 발생하지 않는다`() {
        val member = memberRepository.save(Member.createUser("region-batch@example.com", "encodedPassword", "판매자"))
        val category = categoryRepository.save(Category("지역배치페치"))
        val regions =
            listOf(
                saveGangnamWithDong(),
                saveSeochoWithDong(),
                saveMapoWithDong(),
                saveSongpaWithDong(),
                saveYongsanWithDong(),
            )
        regions.forEachIndexed { index, region ->
            productRepository.save(
                Product.create(
                    member,
                    category,
                    "지역 배치 상품 $index",
                    "지역 배치 상품 설명 $index",
                    BigDecimal.valueOf(10000L * (index + 1)),
                    region,
                ),
            )
        }
        productRepository.flush()
        entityManager.clear()

        val statistics = entityManager.entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        statistics.isStatisticsEnabled = true
        statistics.clear()

        val products =
            productRepository.findAll(
                ProductSpecification.categoryList(category.id),
                Sort.by(Sort.Direction.DESC, "id"),
            )
        // ProductSummaryResponse.from 이 읽는 것과 같은 필드들 — 여기서 지역 프록시가 깨진다.
        products.forEach { product ->
            product.regionCode
            product.regionName
            product.regionFullName
        }

        val queryCount = statistics.prepareStatementCount

        assertThat(products).hasSize(regions.size)
        // 목록 1 + 지역 배치 1. 상품이 5건이든 30건이든 이 값은 늘지 않는다.
        assertThat(queryCount).isLessThanOrEqualTo(2)
    }

    private fun saveSeoul(): Region =
        regionRepository
            .findByCode("1100000000")
            .orElseGet { regionRepository.save(Region.root("1100000000", "서울특별시", "서울특별시")) }

    private fun saveGangnam(): Region =
        regionRepository
            .findByCode("1168000000")
            .orElseGet { regionRepository.save(Region.child("1168000000", 2, saveSeoul(), "서울특별시 강남구", "강남구")) }

    private fun saveGangnamWithDong(): Region = saveDong(saveGangnam(), "1168010100", "서울특별시 강남구 역삼동", "역삼동")

    private fun saveSeochoWithDong(): Region {
        val seocho =
            regionRepository
                .findByCode("1165000000")
                .orElseGet { regionRepository.save(Region.child("1165000000", 2, saveSeoul(), "서울특별시 서초구", "서초구")) }
        return saveDong(seocho, "1165010800", "서울특별시 서초구 서초동", "서초동")
    }

    private fun saveMapoWithDong(): Region {
        val mapo =
            regionRepository
                .findByCode("1144000000")
                .orElseGet { regionRepository.save(Region.child("1144000000", 2, saveSeoul(), "서울특별시 마포구", "마포구")) }
        return saveDong(mapo, "1144012400", "서울특별시 마포구 연남동", "연남동")
    }

    private fun saveSongpaWithDong(): Region {
        val songpa =
            regionRepository
                .findByCode("1171000000")
                .orElseGet { regionRepository.save(Region.child("1171000000", 2, saveSeoul(), "서울특별시 송파구", "송파구")) }
        return saveDong(songpa, "1171010100", "서울특별시 송파구 잠실동", "잠실동")
    }

    private fun saveYongsanWithDong(): Region {
        val yongsan =
            regionRepository
                .findByCode("1117000000")
                .orElseGet { regionRepository.save(Region.child("1117000000", 2, saveSeoul(), "서울특별시 용산구", "용산구")) }
        return saveDong(yongsan, "1117013000", "서울특별시 용산구 이태원동", "이태원동")
    }

    /** 서울(11)과 prefix 2자리가 다른 시도. 시도 필터가 다른 시도를 걸러내는지 확인하는 데 쓴다. */
    private fun saveBusanWithDong(): Region {
        val busan =
            regionRepository
                .findByCode("2600000000")
                .orElseGet { regionRepository.save(Region.root("2600000000", "부산광역시", "부산광역시")) }
        val haeundae =
            regionRepository
                .findByCode("2635000000")
                .orElseGet { regionRepository.save(Region.child("2635000000", 2, busan, "부산광역시 해운대구", "해운대구")) }
        return saveDong(haeundae, "2635010300", "부산광역시 해운대구 우동", "우동")
    }

    private fun saveDong(
        parent: Region,
        code: String,
        fullName: String,
        displayName: String,
    ): Region =
        regionRepository
            .findByCode(code)
            .orElseGet { regionRepository.save(Region.child(code, 3, parent, fullName, displayName)) }
}
