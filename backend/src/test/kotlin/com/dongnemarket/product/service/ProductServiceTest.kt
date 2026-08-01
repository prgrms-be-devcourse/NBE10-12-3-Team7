package com.dongnemarket.product.service

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.global.common.event.ProductPriceChangedEvent
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.manner.entity.MannerScore
import com.dongnemarket.manner.service.MannerScoreService
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.dto.ProductCreateRequest
import com.dongnemarket.product.dto.ProductSearchRequest
import com.dongnemarket.product.dto.ProductStatusUpdateRequest
import com.dongnemarket.product.dto.ProductUpdateRequest
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.ProductImage
import com.dongnemarket.product.entity.TradeStatus
import com.dongnemarket.product.repository.ProductImageRepository
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.repository.query.FluentQuery
import org.springframework.test.util.ReflectionTestUtils
import java.math.BigDecimal
import java.util.Optional
import java.util.function.Function

/**
 * [단위] ProductService — 등록·목록·검색·상세·수정·상태변경·삭제와 이미지 부수효과.
 *
 * 리포지토리는 전부 목이라 정렬·필터 SQL 자체는 검증하지 않는다. 여기서 보는 것은
 * 서비스가 (1) 요청을 어떤 순서로 검증하는지, (2) 저신뢰 판매자 상품을 뒤로 미는 재정렬,
 * (3) 커서 페이지네이션 계산(size+1 조회), (4) 이미지 저장/교체 부수효과다.
 */
@ExtendWith(MockitoExtension::class)
class ProductServiceTest {
    @Mock
    lateinit var memberRepository: MemberRepository

    @Mock
    lateinit var categoryRepository: CategoryRepository

    @Mock
    lateinit var productRepository: ProductRepository

    @Mock
    lateinit var productImageRepository: ProductImageRepository

    @Mock
    lateinit var regionRepository: RegionRepository

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    @Mock
    lateinit var mannerScoreService: MannerScoreService

    @InjectMocks
    lateinit var productService: ProductService

    @Nested
    @DisplayName("상품 등록")
    inner class CreateProduct {
        @Test
        fun `정상 요청이면 상품을 등록하고 기본 상태를 반환한다`() {
            val member = seller()
            val category = category("디지털기기")
            val request = createRequest("아이폰 15", BigDecimal.valueOf(800000))
            given(memberRepository.findById(SELLER_ID)).willReturn(Optional.of(member))
            given(categoryRepository.findById(request.categoryId!!)).willReturn(Optional.of(category))
            given(regionRepository.findByCode(request.regionCode!!)).willReturn(Optional.of(regionByCode(request.regionCode!!)))
            given(productRepository.save(anyValid<Product>())).willAnswer { it.getArgument<Product>(0) }

            val response = productService.createProduct(SELLER_ID, request)

            assertThat(response.title).isEqualTo("아이폰 15")
            assertThat(response.description).isEqualTo("상태 좋은 아이폰입니다.")
            assertThat(response.price).isEqualByComparingTo("800000")
            assertThat(response.tradeStatus).isEqualTo(TradeStatus.ON_SALE)
            assertThat(response.regionCode).isEqualTo("1168010100")
            assertThat(response.regionName).isEqualTo("역삼동")
            assertThat(response.regionFullName).isEqualTo("서울특별시 강남구 역삼동")
            assertThat(response.viewCount).isZero()
            assertThat(response.hidden).isFalse()
        }

        @Test
        fun `회원이 없으면 상품을 등록할 수 없다`() {
            val request = createRequest("아이폰 15", BigDecimal.valueOf(800000))
            given(memberRepository.findById(SELLER_ID)).willReturn(Optional.empty())

            assertBusinessException(
                { productService.createProduct(SELLER_ID, request) },
                ErrorCode.MEMBER_NOT_FOUND,
            )

            verify(productRepository, never()).save(anyValid<Product>())
        }

        @Test
        fun `카테고리가 없으면 상품을 등록할 수 없다`() {
            val request = createRequest("아이폰 15", BigDecimal.valueOf(800000))
            given(memberRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()))
            given(categoryRepository.findById(request.categoryId!!)).willReturn(Optional.empty())

            assertBusinessException(
                { productService.createProduct(SELLER_ID, request) },
                ErrorCode.CATEGORY_NOT_FOUND,
            )

            verify(productRepository, never()).save(anyValid<Product>())
        }

        @Test
        fun `제목이 공백이면 상품을 등록할 수 없다`() {
            val request = createRequest(" ", BigDecimal.valueOf(800000))

            assertBusinessException(
                { productService.createProduct(SELLER_ID, request) },
                ErrorCode.INVALID_PRODUCT_TITLE,
            )

            verify(memberRepository, never()).findById(anyValid())
            verify(productRepository, never()).save(anyValid<Product>())
        }

        @Test
        fun `가격이 음수이면 상품을 등록할 수 없다`() {
            val request = createRequest("아이폰 15", BigDecimal.valueOf(-1))

            assertBusinessException(
                { productService.createProduct(SELLER_ID, request) },
                ErrorCode.INVALID_PRODUCT_PRICE,
            )

            verify(memberRepository, never()).findById(anyValid())
            verify(productRepository, never()).save(anyValid<Product>())
        }

        @Test
        fun `가격이 null이면 상품을 등록할 수 없다`() {
            val request = createRequest("아이폰 15", null)

            assertBusinessException(
                { productService.createProduct(SELLER_ID, request) },
                ErrorCode.INVALID_PRODUCT_PRICE,
            )

            verify(memberRepository, never()).findById(anyValid())
            verify(productRepository, never()).save(anyValid<Product>())
        }
    }

    @Nested
    @DisplayName("상품 목록 조회")
    inner class GetProducts {
        @Test
        fun `전체 상품 목록을 최신 등록순으로 조회한다`() {
            val oldProduct = product(1L, "오래된 상품", BigDecimal.valueOf(10000))
            val newProduct = product(2L, "최신 상품", BigDecimal.valueOf(20000))
            givenFindByReturns(listOf(newProduct, oldProduct))

            val response = productService.getProducts(null, null, 30)

            assertThat(response.items.map { it.title }).containsExactly("최신 상품", "오래된 상품")
            assertThat(response.hasNext).isFalse()
            assertThat(response.nextCursor).isNull()
            verify(productRepository).findByAnySpec()
        }

        @Test
        fun `상품 목록은 size보다 1개 더 조회해 다음 페이지 여부와 커서를 계산한다`() {
            val newestProduct = product(3L, "최신 상품", BigDecimal.valueOf(30000))
            val middleProduct = product(2L, "중간 상품", BigDecimal.valueOf(20000))
            val extraProduct = product(1L, "다음 페이지 확인용 상품", BigDecimal.valueOf(10000))
            givenFindByReturns(listOf(newestProduct, middleProduct, extraProduct))

            val response = productService.getProducts(null, null, 2)

            assertThat(response.items.map { it.productId }).containsExactly(3L, 2L)
            assertThat(response.hasNext).isTrue()
            assertThat(response.nextCursor).isEqualTo(2L)
        }

        @Test
        fun `마지막 페이지이면 nextCursor는 null이다`() {
            val product = product(1L, "마지막 상품", BigDecimal.valueOf(10000))
            givenFindByReturns(listOf(product))

            val response = productService.getProducts(null, 2L, 2)

            assertThat(response.items.map { it.productId }).containsExactly(1L)
            assertThat(response.hasNext).isFalse()
            assertThat(response.nextCursor).isNull()
        }

        @Test
        fun `size가 0 이하이면 기본 크기 30으로 조회한다`() {
            val rows = (1..31).map { index -> product(index.toLong(), "상품 $index", BigDecimal.valueOf(index * 1000L)) }
            givenFindByReturns(rows)

            val response = productService.getProducts(null, null, 0)

            assertThat(response.items).hasSize(30)
            assertThat(response.hasNext).isTrue()
        }

        @Test
        fun `size가 100보다 크면 최대 100개로 제한한다`() {
            val rows = (1..101).map { index -> product(index.toLong(), "상품 $index", BigDecimal.valueOf(index * 1000L)) }
            givenFindByReturns(rows)

            val response = productService.getProducts(null, null, 101)

            assertThat(response.items).hasSize(100)
            assertThat(response.hasNext).isTrue()
        }

        @Test
        fun `상품 목록 regionCode 필터가 3개이면 조회할 수 없다`() {
            assertBusinessException(
                { productService.getProducts(listOf("1168000000", "1144000000", "1171000000"), null, 30) },
                ErrorCode.INVALID_INPUT_VALUE,
            )

            verify(productRepository, never()).findByAnySpec()
        }

        @Test
        fun `카테고리별 상품 목록을 최신 등록순으로 조회한다`() {
            val oldProduct = product("오래된 상품", BigDecimal.valueOf(10000))
            val newProduct = product("최신 상품", BigDecimal.valueOf(20000))
            given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true)
            given(productRepository.findAll(anyValid(), anyValid<Sort>()))
                .willReturn(listOf(newProduct, oldProduct))

            val responses = productService.getProductsByCategory(CATEGORY_ID)

            assertThat(responses.map { it.title }).containsExactly("최신 상품", "오래된 상품")
            verify(productRepository).findAll(anyValid(), anyValid<Sort>())
        }

        @Test
        fun `카테고리가 없으면 카테고리별 상품 목록을 조회할 수 없다`() {
            given(categoryRepository.existsById(CATEGORY_ID)).willReturn(false)

            assertBusinessException(
                { productService.getProductsByCategory(CATEGORY_ID) },
                ErrorCode.CATEGORY_NOT_FOUND,
            )

            verify(productRepository, never()).findAll(anyValid(), anyValid<Sort>())
        }

        @Test
        fun `저신뢰 판매자 상품은 상품 목록에서 노출 순서 뒤로 밀려난다`() {
            val lowTrustProduct = product(3L, "저신뢰 판매자 상품", BigDecimal.valueOf(30000), lowTrustSeller())
            val normalProduct = product(2L, "일반 판매자 상품", BigDecimal.valueOf(20000), seller())
            givenFindByReturns(listOf(lowTrustProduct, normalProduct))
            given(mannerScoreService.getScoresByMemberIds(anyValid())).willReturn(
                mapOf(
                    LOW_TRUST_SELLER_ID to BigDecimal.valueOf(10.0),
                    SELLER_ID to MannerScore.DEFAULT_SCORE,
                ),
            )

            val response = productService.getProducts(null, null, 30)

            assertThat(response.items.map { it.productId }).containsExactly(2L, 3L)
        }

        @Test
        fun `저신뢰 판매자 상품은 카테고리별 상품 목록에서도 노출 순서 뒤로 밀려난다`() {
            val lowTrustProduct = product(3L, "저신뢰 판매자 상품", BigDecimal.valueOf(30000), lowTrustSeller())
            val normalProduct = product(2L, "일반 판매자 상품", BigDecimal.valueOf(20000), seller())
            given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true)
            given(productRepository.findAll(anyValid(), anyValid<Sort>()))
                .willReturn(listOf(lowTrustProduct, normalProduct))
            given(mannerScoreService.getScoresByMemberIds(anyValid()))
                .willReturn(mapOf(LOW_TRUST_SELLER_ID to BigDecimal.valueOf(10.0)))

            val responses = productService.getProductsByCategory(CATEGORY_ID)

            assertThat(responses.map { it.productId }).containsExactly(2L, 3L)
        }
    }

    @Nested
    @DisplayName("내 상품 조회")
    inner class GetMyProducts {
        @Test
        fun `내 상품 목록은 숨김 상품을 포함해서 최신 등록순으로 조회한다`() {
            val oldProduct = product("오래된 내 상품", BigDecimal.valueOf(10000))
            val hiddenProduct = hiddenProduct("숨김 내 상품")
            given(productRepository.findAllByMemberIdAndDeletedAtIsNullOrderByIdDesc(SELLER_ID))
                .willReturn(listOf(hiddenProduct, oldProduct))

            val responses = productService.getMyProducts(SELLER_ID)

            assertThat(responses).hasSize(2)
            assertThat(responses[0].title).isEqualTo("숨김 내 상품")
            assertThat(responses[0].hidden).isTrue()
            assertThat(responses[1].title).isEqualTo("오래된 내 상품")
            assertThat(responses[1].hidden).isFalse()
        }

        @Test
        fun `내 상품이 없으면 빈 목록을 반환한다`() {
            given(productRepository.findAllByMemberIdAndDeletedAtIsNullOrderByIdDesc(SELLER_ID))
                .willReturn(emptyList())

            val responses = productService.getMyProducts(SELLER_ID)

            assertThat(responses).isEmpty()
        }

        @Test
        fun `인증된 사용자 ID가 없으면 내 상품 목록을 조회할 수 없다`() {
            assertBusinessException(
                { productService.getMyProducts(null) },
                ErrorCode.UNAUTHORIZED,
            )

            verify(productRepository, never()).findAllByMemberIdAndDeletedAtIsNullOrderByIdDesc(anyLong())
        }
    }

    @Nested
    @DisplayName("상품 검색")
    inner class SearchProducts {
        @Test
        fun `검색 조건이 유효하면 최신 등록순 요약 응답을 반환한다`() {
            val product = product("맥북 프로", BigDecimal.valueOf(1200000))
            val request =
                ProductSearchRequest(
                    "맥북",
                    CATEGORY_ID,
                    BigDecimal.valueOf(1000000),
                    BigDecimal.valueOf(1500000),
                    "ON_SALE",
                    listOf("1168000000", "1144000000"),
                )
            given(productRepository.findAll(anyValid(), anyValid<Sort>())).willReturn(listOf(product))

            val responses = productService.searchProducts(request)

            assertThat(responses).hasSize(1)
            assertThat(responses[0].title).isEqualTo("맥북 프로")

            val sortCaptor = ArgumentCaptor.forClass(Sort::class.java)
            verify(productRepository).findAll(anyValid(), sortCaptor.capture())
            val idOrder = sortCaptor.value.getOrderFor("id")
            assertThat(idOrder).isNotNull()
            assertThat(idOrder!!.direction).isEqualTo(Sort.Direction.DESC)
        }

        @Test
        fun `저신뢰 판매자 상품은 검색 결과에서도 노출 순서 뒤로 밀려난다`() {
            val lowTrustProduct = product(2L, "저신뢰 판매자 상품", BigDecimal.valueOf(1200000), lowTrustSeller())
            val normalProduct = product(1L, "일반 판매자 상품", BigDecimal.valueOf(1200000), seller())
            val request = ProductSearchRequest(null, null, null, null, null)
            given(productRepository.findAll(anyValid(), anyValid<Sort>()))
                .willReturn(listOf(lowTrustProduct, normalProduct))
            given(mannerScoreService.getScoresByMemberIds(anyValid()))
                .willReturn(mapOf(LOW_TRUST_SELLER_ID to BigDecimal.valueOf(15.0)))

            val responses = productService.searchProducts(request)

            assertThat(responses.map { it.productId }).containsExactly(1L, 2L)
        }

        @Test
        fun `검색 최소 가격이 음수이면 검색할 수 없다`() {
            val request = ProductSearchRequest(null, null, BigDecimal.valueOf(-1), null, null)

            assertInvalidSearchCondition(request)
        }

        @Test
        fun `검색 최대 가격이 음수이면 검색할 수 없다`() {
            val request = ProductSearchRequest(null, null, null, BigDecimal.valueOf(-1), null)

            assertInvalidSearchCondition(request)
        }

        @Test
        fun `검색 최대 가격이 최소 가격보다 작으면 검색할 수 없다`() {
            val request =
                ProductSearchRequest(
                    null,
                    null,
                    BigDecimal.valueOf(20000),
                    BigDecimal.valueOf(10000),
                    null,
                )

            assertInvalidSearchCondition(request)
        }

        @Test
        fun `검색 거래 상태가 유효하지 않으면 검색할 수 없다`() {
            val request = ProductSearchRequest(null, null, null, null, "INVALID")

            assertInvalidTradeStatus { productService.searchProducts(request) }
        }

        @Test
        fun `검색 거래 상태가 공백이면 검색할 수 없다`() {
            val request = ProductSearchRequest(null, null, null, null, " ")

            assertInvalidTradeStatus { productService.searchProducts(request) }
        }

        @Test
        fun `검색 regionCode 필터가 3개이면 검색할 수 없다`() {
            val request =
                ProductSearchRequest(
                    null,
                    null,
                    null,
                    null,
                    null,
                    listOf("1168000000", "1144000000", "1171000000"),
                )

            assertBusinessException(
                { productService.searchProducts(request) },
                ErrorCode.INVALID_INPUT_VALUE,
            )

            verify(productRepository, never()).findAll(anyValid(), anyValid<Sort>())
        }
    }

    @Nested
    @DisplayName("상품 상세 조회")
    inner class GetProduct {
        @Test
        fun `상세 조회에 성공하면 조회수가 1 증가한다`() {
            val product = product("아이폰 15", BigDecimal.valueOf(800000))
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            val response = productService.getProduct(PRODUCT_ID)

            assertThat(response.title).isEqualTo("아이폰 15")
            assertThat(response.description).isEqualTo("상품 설명입니다.")
            assertThat(response.viewCount).isEqualTo(1)
            assertThat(product.viewCount).isEqualTo(1)
            assertThat(response.sellerNickname).isEqualTo("판매자")
        }

        @Test
        fun `상품이 없으면 상세 조회를 할 수 없다`() {
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.empty())

            assertBusinessException(
                { productService.getProduct(PRODUCT_ID) },
                ErrorCode.PRODUCT_NOT_FOUND,
            )
        }

        @Test
        fun `삭제된 상품이면 상세 조회를 할 수 없다`() {
            val product = deletedProduct("아이폰 15")
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            assertBusinessException(
                { productService.getProduct(PRODUCT_ID) },
                ErrorCode.DELETED_PRODUCT,
            )
        }

        @Test
        fun `숨김 상품이면 상세 조회를 할 수 없다`() {
            val product = hiddenProduct("아이폰 15")
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            assertBusinessException(
                { productService.getProduct(PRODUCT_ID) },
                ErrorCode.HIDDEN_PRODUCT,
            )
        }

        @Test
        fun `탈퇴 판매자 상품이면 상세 조회를 할 수 없다`() {
            val product = productBySellerStatus("아이폰 15", MemberStatus.DELETED)
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            assertBusinessException(
                { productService.getProduct(PRODUCT_ID) },
                ErrorCode.PRODUCT_NOT_FOUND,
            )
        }

        @Test
        fun `정지 판매자 상품이면 상세 조회를 할 수 없다`() {
            val product = productBySellerStatus("아이폰 15", MemberStatus.SUSPENDED)
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            assertBusinessException(
                { productService.getProduct(PRODUCT_ID) },
                ErrorCode.PRODUCT_NOT_FOUND,
            )
        }

        @Test
        fun `거래완료 상품이면 상세 조회를 할 수 없다`() {
            val product = completedProduct("아이폰 15")
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            assertBusinessException(
                { productService.getProduct(PRODUCT_ID) },
                ErrorCode.PRODUCT_NOT_FOUND,
            )
        }
    }

    @Nested
    @DisplayName("상품 수정")
    inner class UpdateProduct {
        @Test
        fun `작성자는 상품 정보를 수정할 수 있다`() {
            val product = product("아이폰 15", BigDecimal.valueOf(800000))
            val newCategory = category("생활가전")
            val request = updateRequest("맥북 프로", BigDecimal.valueOf(1500000))
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))
            given(categoryRepository.findById(request.categoryId!!)).willReturn(Optional.of(newCategory))
            given(regionRepository.findByCode(request.regionCode!!)).willReturn(Optional.of(regionByCode(request.regionCode!!)))

            val response = productService.updateProduct(SELLER_ID, PRODUCT_ID, request)

            assertThat(response.title).isEqualTo("맥북 프로")
            assertThat(response.description).isEqualTo("수정된 상품 설명입니다.")
            assertThat(response.price).isEqualByComparingTo("1500000")
            assertThat(response.regionCode).isEqualTo("1165010800")
            assertThat(response.regionName).isEqualTo("서초동")
            assertThat(response.regionFullName).isEqualTo("서울특별시 서초구 서초동")
            assertThat(product.category).isEqualTo(newCategory)
        }

        @Test
        fun `가격이 변경되면 가격 변경 이벤트를 발행한다`() {
            val product = product("아이폰 15", BigDecimal.valueOf(800000))
            val newCategory = category("생활가전")
            val request = updateRequest("맥북 프로", BigDecimal.valueOf(1500000))
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))
            given(categoryRepository.findById(request.categoryId!!)).willReturn(Optional.of(newCategory))
            given(regionRepository.findByCode(request.regionCode!!)).willReturn(Optional.of(regionByCode(request.regionCode!!)))

            productService.updateProduct(SELLER_ID, PRODUCT_ID, request)

            verify(eventPublisher).publishEvent(anyValid<ProductPriceChangedEvent>())
        }

        @Test
        fun `가격이 그대로면 가격 변경 이벤트를 발행하지 않는다`() {
            val product = product("아이폰 15", BigDecimal.valueOf(800000))
            val newCategory = category("생활가전")
            // 가격만 동일(다른 필드는 변경). BigDecimal scale 달라도(800000 vs 800000.00) 발행되면 안 된다.
            val request = updateRequest("맥북 프로", BigDecimal("800000.00"))
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))
            given(categoryRepository.findById(request.categoryId!!)).willReturn(Optional.of(newCategory))
            given(regionRepository.findByCode(request.regionCode!!)).willReturn(Optional.of(regionByCode(request.regionCode!!)))

            productService.updateProduct(SELLER_ID, PRODUCT_ID, request)

            verify(eventPublisher, never()).publishEvent(anyValid<Any>())
        }

        @Test
        fun `수정할 상품이 없으면 수정할 수 없다`() {
            val request = updateRequest("맥북 프로", BigDecimal.valueOf(1500000))
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.empty())

            assertBusinessException(
                { productService.updateProduct(SELLER_ID, PRODUCT_ID, request) },
                ErrorCode.PRODUCT_NOT_FOUND,
            )
        }

        @Test
        fun `삭제된 상품은 수정할 수 없다`() {
            val product = deletedProduct("아이폰 15")
            val request = updateRequest("맥북 프로", BigDecimal.valueOf(1500000))
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            assertBusinessException(
                { productService.updateProduct(SELLER_ID, PRODUCT_ID, request) },
                ErrorCode.DELETED_PRODUCT,
            )
        }

        @Test
        fun `작성자가 아니면 수정할 수 없다`() {
            val product = product("아이폰 15", BigDecimal.valueOf(800000))
            val request = updateRequest("맥북 프로", BigDecimal.valueOf(1500000))
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            assertBusinessException(
                { productService.updateProduct(OTHER_MEMBER_ID, PRODUCT_ID, request) },
                ErrorCode.PRODUCT_OWNER_ONLY,
            )
        }

        @Test
        fun `거래완료 상품은 수정할 수 없다`() {
            val product = completedProduct("아이폰 15")
            val request = updateRequest("맥북 프로", BigDecimal.valueOf(1500000))
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            assertBusinessException(
                { productService.updateProduct(SELLER_ID, PRODUCT_ID, request) },
                ErrorCode.CANNOT_UPDATE_COMPLETED_PRODUCT,
            )
        }

        @Test
        fun `수정 요청 카테고리가 없으면 수정할 수 없다`() {
            val product = product("아이폰 15", BigDecimal.valueOf(800000))
            val request = updateRequest("맥북 프로", BigDecimal.valueOf(1500000))
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))
            given(categoryRepository.findById(request.categoryId!!)).willReturn(Optional.empty())

            assertBusinessException(
                { productService.updateProduct(SELLER_ID, PRODUCT_ID, request) },
                ErrorCode.CATEGORY_NOT_FOUND,
            )
        }

        @Test
        fun `수정 제목이 공백이면 수정할 수 없다`() {
            val request = updateRequest(" ", BigDecimal.valueOf(1500000))

            assertBusinessException(
                { productService.updateProduct(SELLER_ID, PRODUCT_ID, request) },
                ErrorCode.INVALID_PRODUCT_TITLE,
            )

            verify(productRepository, never()).findById(anyValid())
        }

        @Test
        fun `수정 가격이 음수이면 수정할 수 없다`() {
            val request = updateRequest("맥북 프로", BigDecimal.valueOf(-1))

            assertBusinessException(
                { productService.updateProduct(SELLER_ID, PRODUCT_ID, request) },
                ErrorCode.INVALID_PRODUCT_PRICE,
            )

            verify(productRepository, never()).findById(anyValid())
        }

        @Test
        fun `수정 가격이 null이면 수정할 수 없다`() {
            val request = updateRequest("맥북 프로", null)

            assertBusinessException(
                { productService.updateProduct(SELLER_ID, PRODUCT_ID, request) },
                ErrorCode.INVALID_PRODUCT_PRICE,
            )

            verify(productRepository, never()).findById(anyValid())
        }

        @Test
        fun `수정 지역이 지역 마스터에 없으면 수정할 수 없다`() {
            val product = product("아이폰 15", BigDecimal.valueOf(800000))
            val newCategory = category("생활가전")
            val request =
                updateRequest(
                    "맥북 프로",
                    BigDecimal.valueOf(1500000),
                    listOf("https://example.com/update-default.jpg"),
                    0,
                    "서울시 강남구",
                )
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))
            given(categoryRepository.findById(request.categoryId!!)).willReturn(Optional.of(newCategory))
            given(regionRepository.findByCode(request.regionCode!!)).willReturn(Optional.empty())

            assertBusinessException(
                { productService.updateProduct(SELLER_ID, PRODUCT_ID, request) },
                ErrorCode.INVALID_INPUT_VALUE,
            )

            verify(productImageRepository, never()).deleteAllByProductId(anyLong())
        }
    }

    @Nested
    @DisplayName("거래 상태 변경")
    inner class UpdateProductStatus {
        @Test
        fun `작성자는 거래 상태를 변경할 수 있다`() {
            val product = product("아이폰 15", BigDecimal.valueOf(800000))
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            val response =
                productService.updateProductStatus(
                    SELLER_ID,
                    PRODUCT_ID,
                    statusRequest("RESERVED"),
                )

            assertThat(response.tradeStatus).isEqualTo(TradeStatus.RESERVED)
            assertThat(product.tradeStatus).isEqualTo(TradeStatus.RESERVED)
        }

        @Test
        fun `같은 거래 상태를 요청하면 현재 상태를 그대로 반환한다`() {
            val product = product("아이폰 15", BigDecimal.valueOf(800000))
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            val response =
                productService.updateProductStatus(
                    SELLER_ID,
                    PRODUCT_ID,
                    statusRequest("ON_SALE"),
                )

            assertThat(response.tradeStatus).isEqualTo(TradeStatus.ON_SALE)
            assertThat(product.tradeStatus).isEqualTo(TradeStatus.ON_SALE)
        }

        @Test
        fun `거래완료 상품에 거래완료를 다시 요청하면 현재 상태를 그대로 반환한다`() {
            val product = completedProduct("아이폰 15")
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            val response =
                productService.updateProductStatus(
                    SELLER_ID,
                    PRODUCT_ID,
                    statusRequest("COMPLETED"),
                )

            assertThat(response.tradeStatus).isEqualTo(TradeStatus.COMPLETED)
            assertThat(product.tradeStatus).isEqualTo(TradeStatus.COMPLETED)
        }

        @Test
        fun `숨김 상품도 작성자라면 거래 상태를 변경할 수 있다`() {
            val product = hiddenProduct("아이폰 15")
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            val response =
                productService.updateProductStatus(
                    SELLER_ID,
                    PRODUCT_ID,
                    statusRequest("RESERVED"),
                )

            assertThat(response.tradeStatus).isEqualTo(TradeStatus.RESERVED)
            assertThat(product.isHidden).isTrue()
        }

        @Test
        fun `거래 상태를 변경할 상품이 없으면 변경할 수 없다`() {
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.empty())

            assertBusinessException(
                { productService.updateProductStatus(SELLER_ID, PRODUCT_ID, statusRequest("RESERVED")) },
                ErrorCode.PRODUCT_NOT_FOUND,
            )
        }

        @Test
        fun `삭제된 상품은 거래 상태를 변경할 수 없다`() {
            val product = deletedProduct("아이폰 15")
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            assertBusinessException(
                { productService.updateProductStatus(SELLER_ID, PRODUCT_ID, statusRequest("RESERVED")) },
                ErrorCode.DELETED_PRODUCT,
            )
        }

        @Test
        fun `작성자가 아니면 거래 상태를 변경할 수 없다`() {
            val product = product("아이폰 15", BigDecimal.valueOf(800000))
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            assertBusinessException(
                { productService.updateProductStatus(OTHER_MEMBER_ID, PRODUCT_ID, statusRequest("RESERVED")) },
                ErrorCode.PRODUCT_OWNER_ONLY,
            )
        }

        @Test
        fun `거래완료 상품은 다른 거래 상태로 되돌릴 수 없다`() {
            val product = completedProduct("아이폰 15")
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            assertBusinessException(
                { productService.updateProductStatus(SELLER_ID, PRODUCT_ID, statusRequest("ON_SALE")) },
                ErrorCode.CANNOT_CHANGE_COMPLETED_PRODUCT,
            )
        }

        @Test
        fun `거래 상태 값이 유효하지 않으면 변경할 수 없다`() {
            assertInvalidTradeStatus {
                productService.updateProductStatus(SELLER_ID, PRODUCT_ID, statusRequest("INVALID"))
            }
        }

        @Test
        fun `거래 상태 값이 공백이면 변경할 수 없다`() {
            assertInvalidTradeStatus {
                productService.updateProductStatus(SELLER_ID, PRODUCT_ID, statusRequest(" "))
            }
        }

        @Test
        fun `거래 상태 값이 null이면 변경할 수 없다`() {
            assertInvalidTradeStatus {
                productService.updateProductStatus(SELLER_ID, PRODUCT_ID, statusRequest(null))
            }
        }

        @Test
        fun `거래 상태 변경 요청 객체가 null이면 변경할 수 없다`() {
            assertInvalidTradeStatus {
                productService.updateProductStatus(SELLER_ID, PRODUCT_ID, null)
            }
        }
    }

    @Nested
    @DisplayName("상품 삭제")
    inner class DeleteProduct {
        @Test
        fun `작성자는 상품을 논리 삭제할 수 있다`() {
            val product = product("아이폰 15", BigDecimal.valueOf(800000))
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            productService.deleteProduct(SELLER_ID, PRODUCT_ID)

            assertThat(product.isDeleted).isTrue()
            assertThat(product.deletedAt).isNotNull()
        }

        @Test
        fun `거래완료 상품도 작성자라면 논리 삭제할 수 있다`() {
            val product = completedProduct("아이폰 15")
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            productService.deleteProduct(SELLER_ID, PRODUCT_ID)

            assertThat(product.isDeleted).isTrue()
            assertThat(product.tradeStatus).isEqualTo(TradeStatus.COMPLETED)
        }

        @Test
        fun `삭제할 상품이 없으면 삭제할 수 없다`() {
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.empty())

            assertBusinessException(
                { productService.deleteProduct(SELLER_ID, PRODUCT_ID) },
                ErrorCode.PRODUCT_NOT_FOUND,
            )
        }

        @Test
        fun `이미 삭제된 상품은 다시 삭제할 수 없다`() {
            val product = deletedProduct("아이폰 15")
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            assertBusinessException(
                { productService.deleteProduct(SELLER_ID, PRODUCT_ID) },
                ErrorCode.DELETED_PRODUCT,
            )
        }

        @Test
        fun `작성자가 아니면 삭제할 수 없다`() {
            val product = product("아이폰 15", BigDecimal.valueOf(800000))
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))

            assertBusinessException(
                { productService.deleteProduct(OTHER_MEMBER_ID, PRODUCT_ID) },
                ErrorCode.PRODUCT_OWNER_ONLY,
            )
        }
    }

    @Nested
    @DisplayName("접근 가능한 상품 검증")
    inner class ValidateAccessibleProduct {
        @Test
        fun `접근 가능한 상품이면 검증을 통과한다`() {
            given(productRepository.existsByIdAndDeletedAtIsNullAndIsHiddenFalse(PRODUCT_ID)).willReturn(true)

            productService.validateAccessibleProduct(PRODUCT_ID)

            verify(productRepository).existsByIdAndDeletedAtIsNullAndIsHiddenFalse(PRODUCT_ID)
        }

        @Test
        fun `존재하지 않는 상품이면 접근 가능한 상품으로 인정하지 않는다`() {
            given(productRepository.existsByIdAndDeletedAtIsNullAndIsHiddenFalse(PRODUCT_ID)).willReturn(false)

            assertInaccessibleProduct()
        }

        @Test
        fun `삭제된 상품이면 접근 가능한 상품으로 인정하지 않는다`() {
            given(productRepository.existsByIdAndDeletedAtIsNullAndIsHiddenFalse(PRODUCT_ID)).willReturn(false)

            assertInaccessibleProduct()
        }

        @Test
        fun `숨김 상품이면 접근 가능한 상품으로 인정하지 않는다`() {
            given(productRepository.existsByIdAndDeletedAtIsNullAndIsHiddenFalse(PRODUCT_ID)).willReturn(false)

            assertInaccessibleProduct()
        }
    }

    @Nested
    @DisplayName("상품 이미지")
    inner class ProductImages {
        @Test
        fun `이미지 3장과 대표 인덱스로 상품을 등록하면 순서와 대표 이미지가 저장된다`() {
            val member = seller()
            val category = category("디지털기기")
            val request =
                createRequest(
                    "아이폰 15",
                    BigDecimal.valueOf(800000),
                    listOf("https://example.com/1.jpg", "https://example.com/2.jpg", "https://example.com/3.jpg"),
                    1,
                )
            given(memberRepository.findById(SELLER_ID)).willReturn(Optional.of(member))
            given(categoryRepository.findById(request.categoryId!!)).willReturn(Optional.of(category))
            given(regionRepository.findByCode(request.regionCode!!)).willReturn(Optional.of(regionByCode(request.regionCode!!)))
            given(productRepository.save(anyValid<Product>())).willAnswer { it.getArgument<Product>(0) }

            val response = productService.createProduct(SELLER_ID, request)

            val imagesCaptor = imageListCaptor()
            verify(productImageRepository).saveAll(imagesCaptor.capture())
            val images = imagesCaptor.value
            assertThat(images).hasSize(3)
            assertThat(images.map { it.imageUrl })
                .containsExactly("https://example.com/1.jpg", "https://example.com/2.jpg", "https://example.com/3.jpg")
            assertThat(images.map { it.sortOrder }).containsExactly(0, 1, 2)
            assertThat(images.map { it.isRepresentative }).containsExactly(false, true, false)
            assertThat(response.thumbnailUrl).isEqualTo("https://example.com/2.jpg")
        }

        @Test
        fun `상품 수정 시 기존 이미지를 모두 삭제하고 새 이미지로 교체한다`() {
            val product = product("아이폰 15", BigDecimal.valueOf(800000))
            val newCategory = category("생활가전")
            val request =
                updateRequest(
                    "맥북 프로",
                    BigDecimal.valueOf(1500000),
                    listOf("https://example.com/new-1.jpg", "https://example.com/new-2.jpg"),
                    0,
                )
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))
            given(categoryRepository.findById(request.categoryId!!)).willReturn(Optional.of(newCategory))
            given(regionRepository.findByCode(request.regionCode!!)).willReturn(Optional.of(regionByCode(request.regionCode!!)))

            val response = productService.updateProduct(SELLER_ID, PRODUCT_ID, request)

            verify(productImageRepository).deleteAllByProductId(PRODUCT_ID)
            val imagesCaptor = imageListCaptor()
            verify(productImageRepository).saveAll(imagesCaptor.capture())
            val images = imagesCaptor.value
            assertThat(images.map { it.imageUrl })
                .containsExactly("https://example.com/new-1.jpg", "https://example.com/new-2.jpg")
            assertThat(images.map { it.isRepresentative }).containsExactly(true, false)
            assertThat(response.thumbnailUrl).isEqualTo("https://example.com/new-1.jpg")
        }

        @Test
        fun `상품 수정 시 대표 인덱스를 바꾸면 대표 이미지를 재선택한다`() {
            val product = product("아이폰 15", BigDecimal.valueOf(800000))
            val newCategory = category("생활가전")
            val request =
                updateRequest(
                    "맥북 프로",
                    BigDecimal.valueOf(1500000),
                    listOf("https://example.com/same-1.jpg", "https://example.com/same-2.jpg"),
                    1,
                )
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))
            given(categoryRepository.findById(request.categoryId!!)).willReturn(Optional.of(newCategory))
            given(regionRepository.findByCode(request.regionCode!!)).willReturn(Optional.of(regionByCode(request.regionCode!!)))

            val response = productService.updateProduct(SELLER_ID, PRODUCT_ID, request)

            val imagesCaptor = imageListCaptor()
            verify(productImageRepository).saveAll(imagesCaptor.capture())
            assertThat(imagesCaptor.value.map { it.isRepresentative }).containsExactly(false, true)
            assertThat(response.thumbnailUrl).isEqualTo("https://example.com/same-2.jpg")
        }

        @Test
        fun `이미지 5장까지 상품을 등록할 수 있다`() {
            val request =
                createRequest(
                    "아이폰 15",
                    BigDecimal.valueOf(800000),
                    listOf(
                        "https://example.com/1.jpg",
                        "https://example.com/2.jpg",
                        "https://example.com/3.jpg",
                        "https://example.com/4.jpg",
                        "https://example.com/5.jpg",
                    ),
                    0,
                )
            given(memberRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()))
            given(categoryRepository.findById(request.categoryId!!)).willReturn(Optional.of(category("디지털기기")))
            given(regionRepository.findByCode(request.regionCode!!)).willReturn(Optional.of(regionByCode(request.regionCode!!)))
            given(productRepository.save(anyValid<Product>())).willAnswer { it.getArgument<Product>(0) }

            productService.createProduct(SELLER_ID, request)

            val imagesCaptor = imageListCaptor()
            verify(productImageRepository).saveAll(imagesCaptor.capture())
            assertThat(imagesCaptor.value).hasSize(5)
        }

        @Test
        fun `이미지 1장으로 상품을 등록하면 그 이미지가 대표가 된다`() {
            val request =
                createRequest(
                    "아이폰 15",
                    BigDecimal.valueOf(800000),
                    listOf("https://example.com/only.jpg"),
                    0,
                )
            given(memberRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()))
            given(categoryRepository.findById(request.categoryId!!)).willReturn(Optional.of(category("디지털기기")))
            given(regionRepository.findByCode(request.regionCode!!)).willReturn(Optional.of(regionByCode(request.regionCode!!)))
            given(productRepository.save(anyValid<Product>())).willAnswer { it.getArgument<Product>(0) }

            val response = productService.createProduct(SELLER_ID, request)

            val imagesCaptor = imageListCaptor()
            verify(productImageRepository).saveAll(imagesCaptor.capture())
            assertThat(imagesCaptor.value.map { it.isRepresentative }).containsExactly(true)
            assertThat(response.thumbnailUrl).isEqualTo("https://example.com/only.jpg")
        }

        @Test
        fun `대표 인덱스가 마지막 이미지이면 마지막 이미지를 대표로 등록한다`() {
            val request =
                createRequest(
                    "아이폰 15",
                    BigDecimal.valueOf(800000),
                    listOf("https://example.com/1.jpg", "https://example.com/2.jpg", "https://example.com/3.jpg"),
                    2,
                )
            given(memberRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()))
            given(categoryRepository.findById(request.categoryId!!)).willReturn(Optional.of(category("디지털기기")))
            given(regionRepository.findByCode(request.regionCode!!)).willReturn(Optional.of(regionByCode(request.regionCode!!)))
            given(productRepository.save(anyValid<Product>())).willAnswer { it.getArgument<Product>(0) }

            val response = productService.createProduct(SELLER_ID, request)

            assertThat(response.thumbnailUrl).isEqualTo("https://example.com/3.jpg")
        }

        @Test
        fun `이미지 목록이 null이면 상품을 등록할 수 없다`() {
            val request = createRequest("아이폰 15", BigDecimal.valueOf(800000), null, 0)

            assertBusinessException(
                { productService.createProduct(SELLER_ID, request) },
                ErrorCode.INVALID_INPUT_VALUE,
            )
        }

        @Test
        fun `이미지 목록이 비어 있으면 상품을 등록할 수 없다`() {
            val request = createRequest("아이폰 15", BigDecimal.valueOf(800000), emptyList(), 0)

            assertBusinessException(
                { productService.createProduct(SELLER_ID, request) },
                ErrorCode.INVALID_INPUT_VALUE,
            )
        }

        @Test
        fun `이미지가 6장이면 상품을 등록할 수 없다`() {
            val request =
                createRequest(
                    "아이폰 15",
                    BigDecimal.valueOf(800000),
                    listOf(
                        "https://example.com/1.jpg",
                        "https://example.com/2.jpg",
                        "https://example.com/3.jpg",
                        "https://example.com/4.jpg",
                        "https://example.com/5.jpg",
                        "https://example.com/6.jpg",
                    ),
                    0,
                )

            assertBusinessException(
                { productService.createProduct(SELLER_ID, request) },
                ErrorCode.INVALID_INPUT_VALUE,
            )
        }

        @Test
        fun `이미지 URL이 공백이면 상품을 등록할 수 없다`() {
            val request =
                createRequest(
                    "아이폰 15",
                    BigDecimal.valueOf(800000),
                    listOf("https://example.com/1.jpg", " "),
                    0,
                )

            assertBusinessException(
                { productService.createProduct(SELLER_ID, request) },
                ErrorCode.INVALID_INPUT_VALUE,
            )
        }

        @Test
        fun `대표 인덱스가 음수이면 상품을 등록할 수 없다`() {
            val request =
                createRequest(
                    "아이폰 15",
                    BigDecimal.valueOf(800000),
                    listOf("https://example.com/1.jpg"),
                    -1,
                )

            assertBusinessException(
                { productService.createProduct(SELLER_ID, request) },
                ErrorCode.INVALID_INPUT_VALUE,
            )
        }

        @Test
        fun `대표 인덱스가 이미지 크기와 같으면 상품을 등록할 수 없다`() {
            val request =
                createRequest(
                    "아이폰 15",
                    BigDecimal.valueOf(800000),
                    listOf("https://example.com/1.jpg"),
                    1,
                )

            assertBusinessException(
                { productService.createProduct(SELLER_ID, request) },
                ErrorCode.INVALID_INPUT_VALUE,
            )
        }

        @Test
        fun `이미지 없는 기존 상품을 상세 조회하면 썸네일은 null이고 이미지 목록은 빈 배열이다`() {
            val product = product("이미지 없는 상품", BigDecimal.valueOf(10000))
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product))
            given(productImageRepository.findAllByProductIdOrderBySortOrderAsc(PRODUCT_ID)).willReturn(emptyList())

            val response = productService.getProduct(PRODUCT_ID)

            assertThat(response.thumbnailUrl).isNull()
            assertThat(response.imageUrls).isEmpty()
        }

        @Test
        fun `등록 지역이 지역 마스터에 없으면 상품을 등록할 수 없다`() {
            val request =
                createRequest(
                    "아이폰 15",
                    BigDecimal.valueOf(800000),
                    listOf("https://example.com/default.jpg"),
                    0,
                    "강남",
                )
            given(memberRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()))
            given(categoryRepository.findById(request.categoryId!!)).willReturn(Optional.of(category("디지털기기")))
            given(regionRepository.findByCode(request.regionCode!!)).willReturn(Optional.empty())

            assertBusinessException(
                { productService.createProduct(SELLER_ID, request) },
                ErrorCode.INVALID_INPUT_VALUE,
            )

            verify(productRepository, never()).save(anyValid<Product>())
        }
    }

    private fun givenFindByReturns(rows: List<Product>) {
        given(productRepository.findByAnySpec()).willReturn(rows)
    }

    private fun seller(): Member = member(SELLER_ID, "seller@example.com", "판매자")

    private fun lowTrustSeller(): Member = member(LOW_TRUST_SELLER_ID, "low-trust-seller@example.com", "저신뢰판매자")

    private fun member(
        id: Long,
        email: String,
        nickname: String,
    ): Member {
        val member = Member.createUser(email, "encodedPassword", nickname)
        ReflectionTestUtils.setField(member, "id", id)
        return member
    }

    private fun category(name: String): Category = Category(name)

    private fun dongRegion(): Region =
        Region.child(
            "1168010100",
            3,
            Region.child("1168000000", 2, Region.root("1100000000", "서울특별시", "서울특별시"), "서울특별시 강남구", "강남구"),
            "서울특별시 강남구 역삼동",
            "역삼동",
        )

    private fun updateDongRegion(): Region =
        Region.child(
            "1165010800",
            3,
            Region.child("1165000000", 2, Region.root("1100000000", "서울특별시", "서울특별시"), "서울특별시 서초구", "서초구"),
            "서울특별시 서초구 서초동",
            "서초동",
        )

    private fun regionByCode(regionCode: String): Region = if (regionCode == "1165010800") updateDongRegion() else dongRegion()

    private fun product(
        title: String,
        price: BigDecimal,
    ): Product = product(null, title, price)

    private fun product(
        id: Long?,
        title: String,
        price: BigDecimal,
    ): Product = product(id, title, price, seller())

    private fun product(
        id: Long?,
        title: String,
        price: BigDecimal,
        seller: Member,
    ): Product {
        val product =
            Product.create(
                seller,
                category("디지털기기"),
                title,
                "상품 설명입니다.",
                price,
                dongRegion(),
            )
        if (id != null) {
            ReflectionTestUtils.setField(product, "id", id)
        }
        return product
    }

    private fun hiddenProduct(title: String): Product {
        val product = product(title, BigDecimal.valueOf(800000))
        product.hide()
        return product
    }

    private fun deletedProduct(title: String): Product {
        val product = product(title, BigDecimal.valueOf(800000))
        product.softDelete()
        return product
    }

    private fun completedProduct(title: String): Product {
        val product = product(title, BigDecimal.valueOf(800000))
        product.complete()
        return product
    }

    private fun productBySellerStatus(
        title: String,
        status: MemberStatus,
    ): Product {
        val seller = seller()
        seller.changeStatus(status)
        return Product.create(
            seller,
            category("디지털기기"),
            title,
            "상품 설명입니다.",
            BigDecimal.valueOf(800000),
            dongRegion(),
        )
    }

    private fun createRequest(
        title: String,
        price: BigDecimal?,
    ): ProductCreateRequest =
        createRequest(
            title,
            price,
            listOf("https://example.com/default.jpg"),
            0,
        )

    private fun createRequest(
        title: String,
        price: BigDecimal?,
        imageUrls: List<String>?,
        thumbnailIndex: Int,
    ): ProductCreateRequest = createRequest(title, price, imageUrls, thumbnailIndex, "1168010100")

    private fun createRequest(
        title: String,
        price: BigDecimal?,
        imageUrls: List<String>?,
        thumbnailIndex: Int,
        regionCode: String,
    ): ProductCreateRequest =
        ProductCreateRequest(
            CATEGORY_ID,
            title,
            "상태 좋은 아이폰입니다.",
            price,
            regionCode,
            imageUrls,
            thumbnailIndex,
        )

    private fun updateRequest(
        title: String,
        price: BigDecimal?,
    ): ProductUpdateRequest =
        updateRequest(
            title,
            price,
            listOf("https://example.com/update-default.jpg"),
            0,
        )

    private fun updateRequest(
        title: String,
        price: BigDecimal?,
        imageUrls: List<String>?,
        thumbnailIndex: Int,
    ): ProductUpdateRequest = updateRequest(title, price, imageUrls, thumbnailIndex, "1165010800")

    private fun updateRequest(
        title: String,
        price: BigDecimal?,
        imageUrls: List<String>?,
        thumbnailIndex: Int,
        regionCode: String,
    ): ProductUpdateRequest =
        ProductUpdateRequest(
            UPDATE_CATEGORY_ID,
            title,
            "수정된 상품 설명입니다.",
            price,
            regionCode,
            imageUrls,
            thumbnailIndex,
        )

    private fun statusRequest(status: String?): ProductStatusUpdateRequest = ProductStatusUpdateRequest(status)

    private fun assertInvalidSearchCondition(request: ProductSearchRequest) {
        assertBusinessException(
            { productService.searchProducts(request) },
            ErrorCode.INVALID_SEARCH_CONDITION,
        )

        verify(productRepository, never()).findAll(anyValid(), anyValid<Sort>())
    }

    private fun assertInvalidTradeStatus(callable: ThrowingCallable) {
        assertBusinessException(callable, ErrorCode.INVALID_TRADE_STATUS)

        verify(productRepository, never()).findById(anyValid())
    }

    private fun assertInaccessibleProduct() {
        assertBusinessException(
            { productService.validateAccessibleProduct(PRODUCT_ID) },
            ErrorCode.PRODUCT_NOT_FOUND,
        )
    }

    private fun assertBusinessException(
        callable: ThrowingCallable,
        errorCode: ErrorCode,
    ) {
        assertThatThrownBy(callable)
            .isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", errorCode)
    }

    @Suppress("UNCHECKED_CAST")
    private fun imageListCaptor(): ArgumentCaptor<List<ProductImage>> =
        ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<ProductImage>>

    companion object {
        private const val SELLER_ID = 1L
        private const val OTHER_MEMBER_ID = 2L
        private const val LOW_TRUST_SELLER_ID = 3L
        private const val PRODUCT_ID = 1L
        private const val CATEGORY_ID = 1L
        private const val UPDATE_CATEGORY_ID = 2L
    }
}

/**
 * Mockito 의 `any()` 는 null 을 반환한다. Kotlin 의 non-null 파라미터에 그대로 넘기면
 * 널 검사 인트린식에 걸리므로, 타입 파라미터를 거쳐 검사 없이 통과시킨다
 * (manner 도메인 테스트와 동일한 헬퍼).
 */
@Suppress("UNCHECKED_CAST")
private fun <T> anyValid(): T {
    ArgumentMatchers.any<T>()
    return null as T
}

/**
 * `findBy` 는 메서드 자체가 제네릭(`<S, R>`)이라 인자 자리에서 [anyValid] 의 타입을 추론하지 못한다.
 * 매처 타입을 한 곳에 못박아 stub 과 verify 가 같은 시그니처를 쓰게 한다.
 *
 * 반환형이 nullable 인 이유: 매처를 기록하는 호출이라 실제 반환값은 null 이다.
 * non-null 로 선언하면 널 검사 인트린식이 걸린다.
 */
private fun ProductRepository.findByAnySpec(): List<Product>? =
    findBy(
        anyValid<Specification<Product>>(),
        anyValid<Function<FluentQuery.FetchableFluentQuery<Product>, List<Product>>>(),
    )
