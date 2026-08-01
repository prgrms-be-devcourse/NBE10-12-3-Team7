package com.dongnemarket.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import com.dongnemarket.category.entity.Category;
import com.dongnemarket.category.repository.CategoryRepository;
import com.dongnemarket.global.common.event.ProductPriceChangedEvent;
import com.dongnemarket.global.exception.BusinessException;
import com.dongnemarket.global.exception.ErrorCode;
import com.dongnemarket.manner.entity.MannerScore;
import com.dongnemarket.manner.service.MannerScoreService;
import com.dongnemarket.member.entity.Member;
import com.dongnemarket.member.entity.MemberStatus;
import com.dongnemarket.member.repository.MemberRepository;
import com.dongnemarket.product.dto.ProductCreateRequest;
import com.dongnemarket.product.dto.ProductPageResponse;
import com.dongnemarket.product.dto.ProductResponse;
import com.dongnemarket.product.dto.ProductSearchRequest;
import com.dongnemarket.product.dto.ProductStatusUpdateRequest;
import com.dongnemarket.product.dto.ProductSummaryResponse;
import com.dongnemarket.product.dto.ProductUpdateRequest;
import com.dongnemarket.product.entity.Product;
import com.dongnemarket.product.entity.ProductImage;
import com.dongnemarket.product.entity.TradeStatus;
import com.dongnemarket.product.repository.ProductImageRepository;
import com.dongnemarket.product.repository.ProductRepository;
import com.dongnemarket.region.entity.Region;
import com.dongnemarket.region.repository.RegionRepository;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

	private static final Long SELLER_ID = 1L;
	private static final Long OTHER_MEMBER_ID = 2L;
	private static final Long LOW_TRUST_SELLER_ID = 3L;
	private static final Long PRODUCT_ID = 1L;
	private static final Long CATEGORY_ID = 1L;
	private static final Long UPDATE_CATEGORY_ID = 2L;

	@Mock
	MemberRepository memberRepository;

	@Mock
	CategoryRepository categoryRepository;

	@Mock
	ProductRepository productRepository;

	@Mock
	ProductImageRepository productImageRepository;

	@Mock
	RegionRepository regionRepository;

	@Mock
	ApplicationEventPublisher eventPublisher;

	@Mock
	MannerScoreService mannerScoreService;

	@InjectMocks
	ProductService productService;

	@Nested
	@DisplayName("상품 등록")
	class CreateProduct {

		@Test
		@DisplayName("정상 요청이면 상품을 등록하고 기본 상태를 반환한다")
		void createsProduct() {
			Member member = seller();
			Category category = category("디지털기기");
			ProductCreateRequest request = createRequest("아이폰 15", BigDecimal.valueOf(800000));
			given(memberRepository.findById(SELLER_ID)).willReturn(Optional.of(member));
			given(categoryRepository.findById(request.getCategoryId())).willReturn(Optional.of(category));
			given(regionRepository.findByCode(request.getRegionCode())).willReturn(Optional.of(regionByCode(request.getRegionCode())));
			given(productRepository.save(any(Product.class))).willAnswer(invocation -> invocation.getArgument(0));

			ProductResponse response = productService.createProduct(SELLER_ID, request);

			assertThat(response.getTitle()).isEqualTo("아이폰 15");
			assertThat(response.getDescription()).isEqualTo("상태 좋은 아이폰입니다.");
			assertThat(response.getPrice()).isEqualByComparingTo("800000");
			assertThat(response.getTradeStatus()).isEqualTo(TradeStatus.ON_SALE);
			assertThat(response.getRegionCode()).isEqualTo("1168010100");
			assertThat(response.getRegionName()).isEqualTo("역삼동");
			assertThat(response.getRegionFullName()).isEqualTo("서울특별시 강남구 역삼동");
			assertThat(response.getViewCount()).isZero();
			assertThat(response.getHidden()).isFalse();
		}

		@Test
		@DisplayName("회원이 없으면 상품을 등록할 수 없다")
		void throwsMemberNotFoundWhenMemberDoesNotExist() {
			ProductCreateRequest request = createRequest("아이폰 15", BigDecimal.valueOf(800000));
			given(memberRepository.findById(SELLER_ID)).willReturn(Optional.empty());

			assertBusinessException(
					() -> productService.createProduct(SELLER_ID, request),
					ErrorCode.MEMBER_NOT_FOUND
			);

			verify(productRepository, never()).save(any());
		}

		@Test
		@DisplayName("카테고리가 없으면 상품을 등록할 수 없다")
		void throwsCategoryNotFoundWhenCategoryDoesNotExist() {
			ProductCreateRequest request = createRequest("아이폰 15", BigDecimal.valueOf(800000));
			given(memberRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
			given(categoryRepository.findById(request.getCategoryId())).willReturn(Optional.empty());

			assertBusinessException(
					() -> productService.createProduct(SELLER_ID, request),
					ErrorCode.CATEGORY_NOT_FOUND
			);

			verify(productRepository, never()).save(any());
		}

		@Test
		@DisplayName("제목이 공백이면 상품을 등록할 수 없다")
		void throwsInvalidProductTitleWhenTitleIsBlank() {
			ProductCreateRequest request = createRequest(" ", BigDecimal.valueOf(800000));

			assertBusinessException(
					() -> productService.createProduct(SELLER_ID, request),
					ErrorCode.INVALID_PRODUCT_TITLE
			);

			verify(memberRepository, never()).findById(any());
			verify(productRepository, never()).save(any());
		}

		@Test
		@DisplayName("가격이 음수이면 상품을 등록할 수 없다")
		void throwsInvalidProductPriceWhenPriceIsNegative() {
			ProductCreateRequest request = createRequest("아이폰 15", BigDecimal.valueOf(-1));

			assertBusinessException(
					() -> productService.createProduct(SELLER_ID, request),
					ErrorCode.INVALID_PRODUCT_PRICE
			);

			verify(memberRepository, never()).findById(any());
			verify(productRepository, never()).save(any());
		}

		@Test
		@DisplayName("가격이 null이면 상품을 등록할 수 없다")
		void throwsInvalidProductPriceWhenPriceIsNull() {
			ProductCreateRequest request = createRequest("아이폰 15", null);

			assertBusinessException(
					() -> productService.createProduct(SELLER_ID, request),
					ErrorCode.INVALID_PRODUCT_PRICE
			);

			verify(memberRepository, never()).findById(any());
			verify(productRepository, never()).save(any());
		}
	}

	@Nested
	@DisplayName("상품 목록 조회")
	class GetProducts {

			@Test
			@DisplayName("전체 상품 목록을 최신 등록순으로 조회한다")
			void getsProductsInLatestOrder() {
				Product oldProduct = product(1L, "오래된 상품", BigDecimal.valueOf(10000));
				Product newProduct = product(2L, "최신 상품", BigDecimal.valueOf(20000));
				given(productRepository.findBy(anyProductSpecification(), any()))
						.willReturn(List.of(newProduct, oldProduct));

				ProductPageResponse response = productService.getProducts(null, null, 30);

				assertThat(response.getItems()).extracting(ProductSummaryResponse::getTitle)
						.containsExactly("최신 상품", "오래된 상품");
				assertThat(response.getHasNext()).isFalse();
				assertThat(response.getNextCursor()).isNull();
				verify(productRepository).findBy(anyProductSpecification(), any());
			}

			@Test
			@DisplayName("상품 목록은 size보다 1개 더 조회해 다음 페이지 여부와 커서를 계산한다")
			void getsProductsWithCursorPage() {
				Product newestProduct = product(3L, "최신 상품", BigDecimal.valueOf(30000));
				Product middleProduct = product(2L, "중간 상품", BigDecimal.valueOf(20000));
				Product extraProduct = product(1L, "다음 페이지 확인용 상품", BigDecimal.valueOf(10000));
				given(productRepository.findBy(anyProductSpecification(), any()))
						.willReturn(List.of(newestProduct, middleProduct, extraProduct));

				ProductPageResponse response = productService.getProducts(null, null, 2);

				assertThat(response.getItems()).extracting(ProductSummaryResponse::getProductId)
						.containsExactly(3L, 2L);
				assertThat(response.getHasNext()).isTrue();
				assertThat(response.getNextCursor()).isEqualTo(2L);
			}

			@Test
			@DisplayName("마지막 페이지이면 nextCursor는 null이다")
			void returnsNullNextCursorWhenLastPage() {
				Product product = product(1L, "마지막 상품", BigDecimal.valueOf(10000));
				given(productRepository.findBy(anyProductSpecification(), any()))
						.willReturn(List.of(product));

				ProductPageResponse response = productService.getProducts(null, 2L, 2);

				assertThat(response.getItems()).extracting(ProductSummaryResponse::getProductId)
						.containsExactly(1L);
				assertThat(response.getHasNext()).isFalse();
				assertThat(response.getNextCursor()).isNull();
			}

			@Test
			@DisplayName("size가 0 이하이면 기본 크기 30으로 조회한다")
			void usesDefaultSizeWhenSizeIsNotPositive() {
				List<Product> rows = java.util.stream.IntStream.rangeClosed(1, 31)
						.mapToObj(index -> product((long) index, "상품 " + index, BigDecimal.valueOf(index * 1000L)))
						.toList();
				given(productRepository.findBy(anyProductSpecification(), any()))
						.willReturn(rows);

				ProductPageResponse response = productService.getProducts(null, null, 0);

				assertThat(response.getItems()).hasSize(30);
				assertThat(response.getHasNext()).isTrue();
			}

			@Test
			@DisplayName("size가 100보다 크면 최대 100개로 제한한다")
			void clampsSizeToMaxPageSize() {
				List<Product> rows = java.util.stream.IntStream.rangeClosed(1, 101)
						.mapToObj(index -> product((long) index, "상품 " + index, BigDecimal.valueOf(index * 1000L)))
						.toList();
				given(productRepository.findBy(anyProductSpecification(), any()))
						.willReturn(rows);

				ProductPageResponse response = productService.getProducts(null, null, 101);

				assertThat(response.getItems()).hasSize(100);
				assertThat(response.getHasNext()).isTrue();
			}

			@Test
				@DisplayName("상품 목록 regionCode 필터가 3개이면 조회할 수 없다")
				void throwsInvalidInputWhenGettingProductsWithMoreThanTwoRegions() {
					assertBusinessException(
							() -> productService.getProducts(List.of("1168000000", "1144000000", "1171000000"), null, 30),
							ErrorCode.INVALID_INPUT_VALUE
					);

				verify(productRepository, never()).findBy(anyProductSpecification(), any());
			}

		@Test
		@DisplayName("카테고리별 상품 목록을 최신 등록순으로 조회한다")
			void getsProductsByCategoryInLatestOrder() {
				Product oldProduct = product("오래된 상품", BigDecimal.valueOf(10000));
				Product newProduct = product("최신 상품", BigDecimal.valueOf(20000));
				given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);
				given(productRepository.findAll(anyProductSpecification(), any(Sort.class)))
						.willReturn(List.of(newProduct, oldProduct));

				List<ProductSummaryResponse> responses = productService.getProductsByCategory(CATEGORY_ID);

				assertThat(responses).extracting(ProductSummaryResponse::getTitle)
						.containsExactly("최신 상품", "오래된 상품");
				verify(productRepository).findAll(anyProductSpecification(), any(Sort.class));
			}

		@Test
		@DisplayName("카테고리가 없으면 카테고리별 상품 목록을 조회할 수 없다")
		void throwsCategoryNotFoundWhenGettingProductsByMissingCategory() {
			given(categoryRepository.existsById(CATEGORY_ID)).willReturn(false);

				assertBusinessException(
						() -> productService.getProductsByCategory(CATEGORY_ID),
						ErrorCode.CATEGORY_NOT_FOUND
				);

				verify(productRepository, never()).findAll(anyProductSpecification(), any(Sort.class));
			}

		@Test
		@DisplayName("저신뢰 판매자 상품은 상품 목록에서 노출 순서 뒤로 밀려난다")
		void demotesLowTrustSellerProductsInProductList() {
			Product lowTrustProduct = product(3L, "저신뢰 판매자 상품", BigDecimal.valueOf(30000), lowTrustSeller());
			Product normalProduct = product(2L, "일반 판매자 상품", BigDecimal.valueOf(20000), seller());
			given(productRepository.findBy(anyProductSpecification(), any()))
					.willReturn(List.of(lowTrustProduct, normalProduct));
			given(mannerScoreService.getScoresByMemberIds(any())).willReturn(Map.of(
					LOW_TRUST_SELLER_ID, BigDecimal.valueOf(10.0),
					SELLER_ID, MannerScore.DEFAULT_SCORE
			));

			ProductPageResponse response = productService.getProducts(null, null, 30);

			assertThat(response.getItems()).extracting(ProductSummaryResponse::getProductId)
					.containsExactly(2L, 3L);
		}

		@Test
		@DisplayName("저신뢰 판매자 상품은 카테고리별 상품 목록에서도 노출 순서 뒤로 밀려난다")
		void demotesLowTrustSellerProductsInCategoryList() {
			Product lowTrustProduct = product(3L, "저신뢰 판매자 상품", BigDecimal.valueOf(30000), lowTrustSeller());
			Product normalProduct = product(2L, "일반 판매자 상품", BigDecimal.valueOf(20000), seller());
			given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);
			given(productRepository.findAll(anyProductSpecification(), any(Sort.class)))
					.willReturn(List.of(lowTrustProduct, normalProduct));
			given(mannerScoreService.getScoresByMemberIds(any()))
					.willReturn(Map.of(LOW_TRUST_SELLER_ID, BigDecimal.valueOf(10.0)));

			List<ProductSummaryResponse> responses = productService.getProductsByCategory(CATEGORY_ID);

			assertThat(responses).extracting(ProductSummaryResponse::getProductId)
					.containsExactly(2L, 3L);
		}
		}

	@Nested
	@DisplayName("내 상품 조회")
	class GetMyProducts {

		@Test
		@DisplayName("내 상품 목록은 숨김 상품을 포함해서 최신 등록순으로 조회한다")
		void getsMyProductsInLatestOrderIncludingHiddenProducts() {
			Product oldProduct = product("오래된 내 상품", BigDecimal.valueOf(10000));
			Product hiddenProduct = hiddenProduct("숨김 내 상품");
			given(productRepository.findAllByMemberIdAndDeletedAtIsNullOrderByIdDesc(SELLER_ID))
					.willReturn(List.of(hiddenProduct, oldProduct));

			List<ProductSummaryResponse> responses = productService.getMyProducts(SELLER_ID);

			assertThat(responses).hasSize(2);
			assertThat(responses.get(0).getTitle()).isEqualTo("숨김 내 상품");
			assertThat(responses.get(0).getHidden()).isTrue();
			assertThat(responses.get(1).getTitle()).isEqualTo("오래된 내 상품");
			assertThat(responses.get(1).getHidden()).isFalse();
		}

		@Test
		@DisplayName("내 상품이 없으면 빈 목록을 반환한다")
		void returnsEmptyListWhenMyProductsDoNotExist() {
			given(productRepository.findAllByMemberIdAndDeletedAtIsNullOrderByIdDesc(SELLER_ID))
					.willReturn(List.of());

			List<ProductSummaryResponse> responses = productService.getMyProducts(SELLER_ID);

			assertThat(responses).isEmpty();
		}

		@Test
		@DisplayName("인증된 사용자 ID가 없으면 내 상품 목록을 조회할 수 없다")
		void throwsUnauthorizedWhenGettingMyProductsWithoutMemberId() {
			assertBusinessException(
					() -> productService.getMyProducts(null),
					ErrorCode.UNAUTHORIZED
			);

			verify(productRepository, never()).findAllByMemberIdAndDeletedAtIsNullOrderByIdDesc(anyLong());
		}
	}

	@Nested
	@DisplayName("상품 검색")
	class SearchProducts {

		@Test
		@DisplayName("검색 조건이 유효하면 최신 등록순 요약 응답을 반환한다")
		void searchesProducts() {
			Product product = product("맥북 프로", BigDecimal.valueOf(1200000));
			ProductSearchRequest request = new ProductSearchRequest(
					"맥북",
					CATEGORY_ID,
					BigDecimal.valueOf(1000000),
					BigDecimal.valueOf(1500000),
					"ON_SALE",
						List.of("1168000000", "1144000000")
			);
			given(productRepository.findAll(anyProductSpecification(), any(Sort.class))).willReturn(List.of(product));

			List<ProductSummaryResponse> responses = productService.searchProducts(request);

			assertThat(responses).hasSize(1);
			assertThat(responses.get(0).getTitle()).isEqualTo("맥북 프로");

			ArgumentCaptor<Sort> sortCaptor = ArgumentCaptor.forClass(Sort.class);
			verify(productRepository).findAll(anyProductSpecification(), sortCaptor.capture());
			Sort.Order idOrder = sortCaptor.getValue().getOrderFor("id");
			assertThat(idOrder).isNotNull();
			assertThat(idOrder.getDirection()).isEqualTo(Sort.Direction.DESC);
		}

		@Test
		@DisplayName("저신뢰 판매자 상품은 검색 결과에서도 노출 순서 뒤로 밀려난다")
		void demotesLowTrustSellerProductsInSearchResults() {
			Product lowTrustProduct = product(2L, "저신뢰 판매자 상품", BigDecimal.valueOf(1200000), lowTrustSeller());
			Product normalProduct = product(1L, "일반 판매자 상품", BigDecimal.valueOf(1200000), seller());
			ProductSearchRequest request = new ProductSearchRequest(null, null, null, null, null);
			given(productRepository.findAll(anyProductSpecification(), any(Sort.class)))
					.willReturn(List.of(lowTrustProduct, normalProduct));
			given(mannerScoreService.getScoresByMemberIds(any()))
					.willReturn(Map.of(LOW_TRUST_SELLER_ID, BigDecimal.valueOf(15.0)));

			List<ProductSummaryResponse> responses = productService.searchProducts(request);

			assertThat(responses).extracting(ProductSummaryResponse::getProductId)
					.containsExactly(1L, 2L);
		}

		@Test
		@DisplayName("검색 최소 가격이 음수이면 검색할 수 없다")
		void throwsInvalidSearchConditionWhenMinPriceIsNegative() {
			ProductSearchRequest request = new ProductSearchRequest(null, null, BigDecimal.valueOf(-1), null, null);

			assertInvalidSearchCondition(request);
		}

		@Test
		@DisplayName("검색 최대 가격이 음수이면 검색할 수 없다")
		void throwsInvalidSearchConditionWhenMaxPriceIsNegative() {
			ProductSearchRequest request = new ProductSearchRequest(null, null, null, BigDecimal.valueOf(-1), null);

			assertInvalidSearchCondition(request);
		}

		@Test
		@DisplayName("검색 최대 가격이 최소 가격보다 작으면 검색할 수 없다")
		void throwsInvalidSearchConditionWhenMaxPriceIsLessThanMinPrice() {
			ProductSearchRequest request = new ProductSearchRequest(
					null,
					null,
					BigDecimal.valueOf(20000),
					BigDecimal.valueOf(10000),
					null
			);

			assertInvalidSearchCondition(request);
		}

		@Test
		@DisplayName("검색 거래 상태가 유효하지 않으면 검색할 수 없다")
		void throwsInvalidTradeStatusWhenSearchingWithInvalidTradeStatus() {
			ProductSearchRequest request = new ProductSearchRequest(null, null, null, null, "INVALID");

			assertInvalidTradeStatus(() -> productService.searchProducts(request));
		}

		@Test
		@DisplayName("검색 거래 상태가 공백이면 검색할 수 없다")
		void throwsInvalidTradeStatusWhenSearchingWithBlankTradeStatus() {
			ProductSearchRequest request = new ProductSearchRequest(null, null, null, null, " ");

			assertInvalidTradeStatus(() -> productService.searchProducts(request));
		}

		@Test
			@DisplayName("검색 regionCode 필터가 3개이면 검색할 수 없다")
			void throwsInvalidInputWhenSearchingWithMoreThanTwoRegions() {
				ProductSearchRequest request = new ProductSearchRequest(
						null,
						null,
						null,
						null,
						null,
						List.of("1168000000", "1144000000", "1171000000")
				);

			assertBusinessException(
					() -> productService.searchProducts(request),
					ErrorCode.INVALID_INPUT_VALUE
			);

			verify(productRepository, never()).findAll(anyProductSpecification(), any(Sort.class));
		}
	}

	@Nested
	@DisplayName("상품 상세 조회")
	class GetProduct {

		@Test
		@DisplayName("상세 조회에 성공하면 조회수가 1 증가한다")
		void getsProductAndIncreasesViewCount() {
			Product product = product("아이폰 15", BigDecimal.valueOf(800000));
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			ProductResponse response = productService.getProduct(PRODUCT_ID);

			assertThat(response.getTitle()).isEqualTo("아이폰 15");
			assertThat(response.getDescription()).isEqualTo("상품 설명입니다.");
			assertThat(response.getViewCount()).isEqualTo(1);
			assertThat(product.getViewCount()).isEqualTo(1);
			assertThat(response.getSellerNickname()).isEqualTo("판매자");
		}

		@Test
		@DisplayName("상품이 없으면 상세 조회를 할 수 없다")
		void throwsProductNotFoundWhenProductDoesNotExist() {
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.empty());

			assertBusinessException(
					() -> productService.getProduct(PRODUCT_ID),
					ErrorCode.PRODUCT_NOT_FOUND
			);
		}

		@Test
		@DisplayName("삭제된 상품이면 상세 조회를 할 수 없다")
		void throwsDeletedProductWhenProductIsDeleted() {
			Product product = deletedProduct("아이폰 15");
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			assertBusinessException(
					() -> productService.getProduct(PRODUCT_ID),
					ErrorCode.DELETED_PRODUCT
			);
		}

		@Test
		@DisplayName("숨김 상품이면 상세 조회를 할 수 없다")
			void throwsHiddenProductWhenProductIsHidden() {
				Product product = hiddenProduct("아이폰 15");
				given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

				assertBusinessException(
						() -> productService.getProduct(PRODUCT_ID),
						ErrorCode.HIDDEN_PRODUCT
				);
			}

			@Test
			@DisplayName("탈퇴 판매자 상품이면 상세 조회를 할 수 없다")
			void throwsProductNotFoundWhenSellerIsDeleted() {
				Product product = productBySellerStatus("아이폰 15", MemberStatus.DELETED);
				given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

				assertBusinessException(
						() -> productService.getProduct(PRODUCT_ID),
						ErrorCode.PRODUCT_NOT_FOUND
				);
			}

			@Test
			@DisplayName("정지 판매자 상품이면 상세 조회를 할 수 없다")
			void throwsProductNotFoundWhenSellerIsSuspended() {
				Product product = productBySellerStatus("아이폰 15", MemberStatus.SUSPENDED);
				given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

				assertBusinessException(
						() -> productService.getProduct(PRODUCT_ID),
						ErrorCode.PRODUCT_NOT_FOUND
				);
			}

			@Test
			@DisplayName("거래완료 상품이면 상세 조회를 할 수 없다")
			void throwsProductNotFoundWhenProductIsCompleted() {
				Product product = completedProduct("아이폰 15");
				given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

				assertBusinessException(
						() -> productService.getProduct(PRODUCT_ID),
						ErrorCode.PRODUCT_NOT_FOUND
				);
			}
		}

	@Nested
	@DisplayName("상품 수정")
	class UpdateProduct {

		@Test
		@DisplayName("작성자는 상품 정보를 수정할 수 있다")
		void updatesProductByOwner() {
			Product product = product("아이폰 15", BigDecimal.valueOf(800000));
			Category newCategory = category("생활가전");
			ProductUpdateRequest request = updateRequest("맥북 프로", BigDecimal.valueOf(1500000));
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(categoryRepository.findById(request.getCategoryId())).willReturn(Optional.of(newCategory));
			given(regionRepository.findByCode(request.getRegionCode())).willReturn(Optional.of(regionByCode(request.getRegionCode())));

			ProductResponse response = productService.updateProduct(SELLER_ID, PRODUCT_ID, request);

			assertThat(response.getTitle()).isEqualTo("맥북 프로");
			assertThat(response.getDescription()).isEqualTo("수정된 상품 설명입니다.");
			assertThat(response.getPrice()).isEqualByComparingTo("1500000");
			assertThat(response.getRegionCode()).isEqualTo("1165010800");
			assertThat(response.getRegionName()).isEqualTo("서초동");
			assertThat(response.getRegionFullName()).isEqualTo("서울특별시 서초구 서초동");
			assertThat(product.getCategory()).isEqualTo(newCategory);
		}

		@Test
		@DisplayName("가격이 변경되면 가격 변경 이벤트를 발행한다")
		void publishesEventWhenPriceChanged() {
			Product product = product("아이폰 15", BigDecimal.valueOf(800000));
			Category newCategory = category("생활가전");
			ProductUpdateRequest request = updateRequest("맥북 프로", BigDecimal.valueOf(1500000));
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(categoryRepository.findById(request.getCategoryId())).willReturn(Optional.of(newCategory));
			given(regionRepository.findByCode(request.getRegionCode())).willReturn(Optional.of(regionByCode(request.getRegionCode())));

			productService.updateProduct(SELLER_ID, PRODUCT_ID, request);

			verify(eventPublisher).publishEvent(any(ProductPriceChangedEvent.class));
		}

		@Test
		@DisplayName("가격이 그대로면 가격 변경 이벤트를 발행하지 않는다")
		void doesNotPublishEventWhenPriceUnchanged() {
			Product product = product("아이폰 15", BigDecimal.valueOf(800000));
			Category newCategory = category("생활가전");
			// 가격만 동일(다른 필드는 변경). BigDecimal scale 달라도(800000 vs 800000.00) 발행되면 안 된다.
			ProductUpdateRequest request = updateRequest("맥북 프로", new BigDecimal("800000.00"));
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(categoryRepository.findById(request.getCategoryId())).willReturn(Optional.of(newCategory));
			given(regionRepository.findByCode(request.getRegionCode())).willReturn(Optional.of(regionByCode(request.getRegionCode())));

			productService.updateProduct(SELLER_ID, PRODUCT_ID, request);

			verify(eventPublisher, never()).publishEvent(any());
		}

		@Test
		@DisplayName("수정할 상품이 없으면 수정할 수 없다")
		void throwsProductNotFoundWhenUpdatingMissingProduct() {
			ProductUpdateRequest request = updateRequest("맥북 프로", BigDecimal.valueOf(1500000));
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.empty());

			assertBusinessException(
					() -> productService.updateProduct(SELLER_ID, PRODUCT_ID, request),
					ErrorCode.PRODUCT_NOT_FOUND
			);
		}

		@Test
		@DisplayName("삭제된 상품은 수정할 수 없다")
		void throwsDeletedProductWhenUpdatingDeletedProduct() {
			Product product = deletedProduct("아이폰 15");
			ProductUpdateRequest request = updateRequest("맥북 프로", BigDecimal.valueOf(1500000));
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			assertBusinessException(
					() -> productService.updateProduct(SELLER_ID, PRODUCT_ID, request),
					ErrorCode.DELETED_PRODUCT
			);
		}

		@Test
		@DisplayName("작성자가 아니면 수정할 수 없다")
		void throwsProductOwnerOnlyWhenUpdatingByNonOwner() {
			Product product = product("아이폰 15", BigDecimal.valueOf(800000));
			ProductUpdateRequest request = updateRequest("맥북 프로", BigDecimal.valueOf(1500000));
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			assertBusinessException(
					() -> productService.updateProduct(OTHER_MEMBER_ID, PRODUCT_ID, request),
					ErrorCode.PRODUCT_OWNER_ONLY
			);
		}

		@Test
		@DisplayName("거래완료 상품은 수정할 수 없다")
		void throwsCannotUpdateCompletedProductWhenProductIsCompleted() {
			Product product = completedProduct("아이폰 15");
			ProductUpdateRequest request = updateRequest("맥북 프로", BigDecimal.valueOf(1500000));
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			assertBusinessException(
					() -> productService.updateProduct(SELLER_ID, PRODUCT_ID, request),
					ErrorCode.CANNOT_UPDATE_COMPLETED_PRODUCT
			);
		}

		@Test
		@DisplayName("수정 요청 카테고리가 없으면 수정할 수 없다")
		void throwsCategoryNotFoundWhenUpdatingWithMissingCategory() {
			Product product = product("아이폰 15", BigDecimal.valueOf(800000));
			ProductUpdateRequest request = updateRequest("맥북 프로", BigDecimal.valueOf(1500000));
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(categoryRepository.findById(request.getCategoryId())).willReturn(Optional.empty());

			assertBusinessException(
					() -> productService.updateProduct(SELLER_ID, PRODUCT_ID, request),
					ErrorCode.CATEGORY_NOT_FOUND
			);
		}

		@Test
		@DisplayName("수정 제목이 공백이면 수정할 수 없다")
		void throwsInvalidProductTitleWhenUpdatingWithBlankTitle() {
			ProductUpdateRequest request = updateRequest(" ", BigDecimal.valueOf(1500000));

			assertBusinessException(
					() -> productService.updateProduct(SELLER_ID, PRODUCT_ID, request),
					ErrorCode.INVALID_PRODUCT_TITLE
			);

			verify(productRepository, never()).findById(any());
		}

		@Test
		@DisplayName("수정 가격이 음수이면 수정할 수 없다")
		void throwsInvalidProductPriceWhenUpdatingWithNegativePrice() {
			ProductUpdateRequest request = updateRequest("맥북 프로", BigDecimal.valueOf(-1));

			assertBusinessException(
					() -> productService.updateProduct(SELLER_ID, PRODUCT_ID, request),
					ErrorCode.INVALID_PRODUCT_PRICE
			);

			verify(productRepository, never()).findById(any());
		}

		@Test
		@DisplayName("수정 가격이 null이면 수정할 수 없다")
		void throwsInvalidProductPriceWhenUpdatingWithNullPrice() {
			ProductUpdateRequest request = updateRequest("맥북 프로", null);

			assertBusinessException(
					() -> productService.updateProduct(SELLER_ID, PRODUCT_ID, request),
					ErrorCode.INVALID_PRODUCT_PRICE
			);

			verify(productRepository, never()).findById(any());
		}

		@Test
		@DisplayName("수정 지역이 지역 마스터에 없으면 수정할 수 없다")
		void throwsInvalidInputWhenUpdatingWithUnknownRegion() {
			Product product = product("아이폰 15", BigDecimal.valueOf(800000));
			Category newCategory = category("생활가전");
			ProductUpdateRequest request = updateRequest(
					"맥북 프로",
					BigDecimal.valueOf(1500000),
					List.of("https://example.com/update-default.jpg"),
					0,
					"서울시 강남구"
			);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(categoryRepository.findById(request.getCategoryId())).willReturn(Optional.of(newCategory));
			given(regionRepository.findByCode(request.getRegionCode())).willReturn(Optional.empty());

			assertBusinessException(
					() -> productService.updateProduct(SELLER_ID, PRODUCT_ID, request),
					ErrorCode.INVALID_INPUT_VALUE
			);

			verify(productImageRepository, never()).deleteAllByProductId(anyLong());
		}
	}

	@Nested
	@DisplayName("거래 상태 변경")
	class UpdateProductStatus {

		@Test
		@DisplayName("작성자는 거래 상태를 변경할 수 있다")
		void updatesProductStatusByOwner() {
			Product product = product("아이폰 15", BigDecimal.valueOf(800000));
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			ProductResponse response = productService.updateProductStatus(
					SELLER_ID,
					PRODUCT_ID,
					statusRequest("RESERVED")
			);

			assertThat(response.getTradeStatus()).isEqualTo(TradeStatus.RESERVED);
			assertThat(product.getTradeStatus()).isEqualTo(TradeStatus.RESERVED);
		}

		@Test
		@DisplayName("같은 거래 상태를 요청하면 현재 상태를 그대로 반환한다")
		void keepsProductStatusWhenRequestingSameStatus() {
			Product product = product("아이폰 15", BigDecimal.valueOf(800000));
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			ProductResponse response = productService.updateProductStatus(
					SELLER_ID,
					PRODUCT_ID,
					statusRequest("ON_SALE")
			);

			assertThat(response.getTradeStatus()).isEqualTo(TradeStatus.ON_SALE);
			assertThat(product.getTradeStatus()).isEqualTo(TradeStatus.ON_SALE);
		}

		@Test
		@DisplayName("거래완료 상품에 거래완료를 다시 요청하면 현재 상태를 그대로 반환한다")
		void keepsCompletedProductStatusWhenRequestingCompletedAgain() {
			Product product = completedProduct("아이폰 15");
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			ProductResponse response = productService.updateProductStatus(
					SELLER_ID,
					PRODUCT_ID,
					statusRequest("COMPLETED")
			);

			assertThat(response.getTradeStatus()).isEqualTo(TradeStatus.COMPLETED);
			assertThat(product.getTradeStatus()).isEqualTo(TradeStatus.COMPLETED);
		}

		@Test
		@DisplayName("숨김 상품도 작성자라면 거래 상태를 변경할 수 있다")
		void updatesHiddenProductStatusByOwner() {
			Product product = hiddenProduct("아이폰 15");
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			ProductResponse response = productService.updateProductStatus(
					SELLER_ID,
					PRODUCT_ID,
					statusRequest("RESERVED")
			);

			assertThat(response.getTradeStatus()).isEqualTo(TradeStatus.RESERVED);
			assertThat(product.isHidden()).isTrue();
		}

		@Test
		@DisplayName("거래 상태를 변경할 상품이 없으면 변경할 수 없다")
		void throwsProductNotFoundWhenUpdatingStatusOfMissingProduct() {
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.empty());

			assertBusinessException(
					() -> productService.updateProductStatus(SELLER_ID, PRODUCT_ID, statusRequest("RESERVED")),
					ErrorCode.PRODUCT_NOT_FOUND
			);
		}

		@Test
		@DisplayName("삭제된 상품은 거래 상태를 변경할 수 없다")
		void throwsDeletedProductWhenUpdatingStatusOfDeletedProduct() {
			Product product = deletedProduct("아이폰 15");
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			assertBusinessException(
					() -> productService.updateProductStatus(SELLER_ID, PRODUCT_ID, statusRequest("RESERVED")),
					ErrorCode.DELETED_PRODUCT
			);
		}

		@Test
		@DisplayName("작성자가 아니면 거래 상태를 변경할 수 없다")
		void throwsProductOwnerOnlyWhenUpdatingStatusByNonOwner() {
			Product product = product("아이폰 15", BigDecimal.valueOf(800000));
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			assertBusinessException(
					() -> productService.updateProductStatus(OTHER_MEMBER_ID, PRODUCT_ID, statusRequest("RESERVED")),
					ErrorCode.PRODUCT_OWNER_ONLY
			);
		}

		@Test
		@DisplayName("거래완료 상품은 다른 거래 상태로 되돌릴 수 없다")
		void throwsCannotChangeCompletedProductWhenUpdatingCompletedStatus() {
			Product product = completedProduct("아이폰 15");
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			assertBusinessException(
					() -> productService.updateProductStatus(SELLER_ID, PRODUCT_ID, statusRequest("ON_SALE")),
					ErrorCode.CANNOT_CHANGE_COMPLETED_PRODUCT
			);
		}

		@Test
		@DisplayName("거래 상태 값이 유효하지 않으면 변경할 수 없다")
		void throwsInvalidTradeStatusWhenUpdatingWithInvalidStatus() {
			assertInvalidTradeStatus(
					() -> productService.updateProductStatus(SELLER_ID, PRODUCT_ID, statusRequest("INVALID"))
			);
		}

		@Test
		@DisplayName("거래 상태 값이 공백이면 변경할 수 없다")
		void throwsInvalidTradeStatusWhenUpdatingWithBlankStatus() {
			assertInvalidTradeStatus(
					() -> productService.updateProductStatus(SELLER_ID, PRODUCT_ID, statusRequest(" "))
			);
		}

		@Test
		@DisplayName("거래 상태 값이 null이면 변경할 수 없다")
		void throwsInvalidTradeStatusWhenUpdatingWithNullStatus() {
			assertInvalidTradeStatus(
					() -> productService.updateProductStatus(SELLER_ID, PRODUCT_ID, statusRequest(null))
			);
		}

		@Test
		@DisplayName("거래 상태 변경 요청 객체가 null이면 변경할 수 없다")
		void throwsInvalidTradeStatusWhenUpdatingWithNullRequest() {
			assertInvalidTradeStatus(
					() -> productService.updateProductStatus(SELLER_ID, PRODUCT_ID, null)
			);
		}
	}

	@Nested
	@DisplayName("상품 삭제")
	class DeleteProduct {

		@Test
		@DisplayName("작성자는 상품을 논리 삭제할 수 있다")
		void deletesProductByOwner() {
			Product product = product("아이폰 15", BigDecimal.valueOf(800000));
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			productService.deleteProduct(SELLER_ID, PRODUCT_ID);

			assertThat(product.isDeleted()).isTrue();
			assertThat(product.getDeletedAt()).isNotNull();
		}

		@Test
		@DisplayName("거래완료 상품도 작성자라면 논리 삭제할 수 있다")
		void deletesCompletedProductByOwner() {
			Product product = completedProduct("아이폰 15");
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			productService.deleteProduct(SELLER_ID, PRODUCT_ID);

			assertThat(product.isDeleted()).isTrue();
			assertThat(product.getTradeStatus()).isEqualTo(TradeStatus.COMPLETED);
		}

		@Test
		@DisplayName("삭제할 상품이 없으면 삭제할 수 없다")
		void throwsProductNotFoundWhenDeletingMissingProduct() {
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.empty());

			assertBusinessException(
					() -> productService.deleteProduct(SELLER_ID, PRODUCT_ID),
					ErrorCode.PRODUCT_NOT_FOUND
			);
		}

		@Test
		@DisplayName("이미 삭제된 상품은 다시 삭제할 수 없다")
		void throwsDeletedProductWhenDeletingDeletedProduct() {
			Product product = deletedProduct("아이폰 15");
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			assertBusinessException(
					() -> productService.deleteProduct(SELLER_ID, PRODUCT_ID),
					ErrorCode.DELETED_PRODUCT
			);
		}

		@Test
		@DisplayName("작성자가 아니면 삭제할 수 없다")
		void throwsProductOwnerOnlyWhenDeletingByNonOwner() {
			Product product = product("아이폰 15", BigDecimal.valueOf(800000));
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

			assertBusinessException(
					() -> productService.deleteProduct(OTHER_MEMBER_ID, PRODUCT_ID),
					ErrorCode.PRODUCT_OWNER_ONLY
			);
		}
	}

	@Nested
	@DisplayName("접근 가능한 상품 검증")
	class ValidateAccessibleProduct {

		@Test
		@DisplayName("접근 가능한 상품이면 검증을 통과한다")
		void validatesAccessibleProduct() {
			given(productRepository.existsByIdAndDeletedAtIsNullAndIsHiddenFalse(PRODUCT_ID)).willReturn(true);

			productService.validateAccessibleProduct(PRODUCT_ID);

			verify(productRepository).existsByIdAndDeletedAtIsNullAndIsHiddenFalse(PRODUCT_ID);
		}

		@Test
		@DisplayName("존재하지 않는 상품이면 접근 가능한 상품으로 인정하지 않는다")
		void throwsProductNotFoundWhenValidatingMissingProduct() {
			given(productRepository.existsByIdAndDeletedAtIsNullAndIsHiddenFalse(PRODUCT_ID)).willReturn(false);

			assertInaccessibleProduct();
		}

		@Test
		@DisplayName("삭제된 상품이면 접근 가능한 상품으로 인정하지 않는다")
		void throwsProductNotFoundWhenValidatingDeletedProduct() {
			given(productRepository.existsByIdAndDeletedAtIsNullAndIsHiddenFalse(PRODUCT_ID)).willReturn(false);

			assertInaccessibleProduct();
		}

		@Test
		@DisplayName("숨김 상품이면 접근 가능한 상품으로 인정하지 않는다")
		void throwsProductNotFoundWhenValidatingHiddenProduct() {
			given(productRepository.existsByIdAndDeletedAtIsNullAndIsHiddenFalse(PRODUCT_ID)).willReturn(false);

			assertInaccessibleProduct();
		}
	}

	@Nested
	@DisplayName("상품 이미지")
	class ProductImages {

		@Test
		@DisplayName("이미지 3장과 대표 인덱스로 상품을 등록하면 순서와 대표 이미지가 저장된다")
		void createsProductWithImagesAndThumbnail() {
			Member member = seller();
			Category category = category("디지털기기");
			ProductCreateRequest request = createRequest(
					"아이폰 15",
					BigDecimal.valueOf(800000),
					List.of("https://example.com/1.jpg", "https://example.com/2.jpg", "https://example.com/3.jpg"),
					1
			);
			given(memberRepository.findById(SELLER_ID)).willReturn(Optional.of(member));
			given(categoryRepository.findById(request.getCategoryId())).willReturn(Optional.of(category));
			given(regionRepository.findByCode(request.getRegionCode())).willReturn(Optional.of(regionByCode(request.getRegionCode())));
			given(productRepository.save(any(Product.class))).willAnswer(invocation -> invocation.getArgument(0));

			ProductResponse response = productService.createProduct(SELLER_ID, request);

			ArgumentCaptor<List<ProductImage>> imagesCaptor = imageListCaptor();
			verify(productImageRepository).saveAll(imagesCaptor.capture());
			List<ProductImage> images = imagesCaptor.getValue();
			assertThat(images).hasSize(3);
			assertThat(images).extracting(ProductImage::getImageUrl)
					.containsExactly("https://example.com/1.jpg", "https://example.com/2.jpg", "https://example.com/3.jpg");
			assertThat(images).extracting(ProductImage::getSortOrder)
					.containsExactly(0, 1, 2);
			assertThat(images).extracting(ProductImage::isRepresentative)
					.containsExactly(false, true, false);
			assertThat(response.getThumbnailUrl()).isEqualTo("https://example.com/2.jpg");
		}

		@Test
		@DisplayName("상품 수정 시 기존 이미지를 모두 삭제하고 새 이미지로 교체한다")
		void replacesProductImagesWhenUpdatingProduct() {
			Product product = product("아이폰 15", BigDecimal.valueOf(800000));
			Category newCategory = category("생활가전");
			ProductUpdateRequest request = updateRequest(
					"맥북 프로",
					BigDecimal.valueOf(1500000),
					List.of("https://example.com/new-1.jpg", "https://example.com/new-2.jpg"),
					0
			);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(categoryRepository.findById(request.getCategoryId())).willReturn(Optional.of(newCategory));
			given(regionRepository.findByCode(request.getRegionCode())).willReturn(Optional.of(regionByCode(request.getRegionCode())));

			ProductResponse response = productService.updateProduct(SELLER_ID, PRODUCT_ID, request);

			verify(productImageRepository).deleteAllByProductId(PRODUCT_ID);
			ArgumentCaptor<List<ProductImage>> imagesCaptor = imageListCaptor();
			verify(productImageRepository).saveAll(imagesCaptor.capture());
			List<ProductImage> images = imagesCaptor.getValue();
			assertThat(images).extracting(ProductImage::getImageUrl)
					.containsExactly("https://example.com/new-1.jpg", "https://example.com/new-2.jpg");
			assertThat(images).extracting(ProductImage::isRepresentative)
					.containsExactly(true, false);
			assertThat(response.getThumbnailUrl()).isEqualTo("https://example.com/new-1.jpg");
		}

		@Test
		@DisplayName("상품 수정 시 대표 인덱스를 바꾸면 대표 이미지를 재선택한다")
		void reselectsThumbnailWhenUpdatingProduct() {
			Product product = product("아이폰 15", BigDecimal.valueOf(800000));
			Category newCategory = category("생활가전");
			ProductUpdateRequest request = updateRequest(
					"맥북 프로",
					BigDecimal.valueOf(1500000),
					List.of("https://example.com/same-1.jpg", "https://example.com/same-2.jpg"),
					1
			);
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(categoryRepository.findById(request.getCategoryId())).willReturn(Optional.of(newCategory));
			given(regionRepository.findByCode(request.getRegionCode())).willReturn(Optional.of(regionByCode(request.getRegionCode())));

			ProductResponse response = productService.updateProduct(SELLER_ID, PRODUCT_ID, request);

			ArgumentCaptor<List<ProductImage>> imagesCaptor = imageListCaptor();
			verify(productImageRepository).saveAll(imagesCaptor.capture());
			assertThat(imagesCaptor.getValue()).extracting(ProductImage::isRepresentative)
					.containsExactly(false, true);
			assertThat(response.getThumbnailUrl()).isEqualTo("https://example.com/same-2.jpg");
		}

		@Test
		@DisplayName("이미지 5장까지 상품을 등록할 수 있다")
		void createsProductWithFiveImages() {
			ProductCreateRequest request = createRequest(
					"아이폰 15",
					BigDecimal.valueOf(800000),
					List.of(
							"https://example.com/1.jpg",
							"https://example.com/2.jpg",
							"https://example.com/3.jpg",
							"https://example.com/4.jpg",
							"https://example.com/5.jpg"
					),
					0
			);
			given(memberRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
			given(categoryRepository.findById(request.getCategoryId())).willReturn(Optional.of(category("디지털기기")));
			given(regionRepository.findByCode(request.getRegionCode())).willReturn(Optional.of(regionByCode(request.getRegionCode())));
			given(productRepository.save(any(Product.class))).willAnswer(invocation -> invocation.getArgument(0));

			productService.createProduct(SELLER_ID, request);

			ArgumentCaptor<List<ProductImage>> imagesCaptor = imageListCaptor();
			verify(productImageRepository).saveAll(imagesCaptor.capture());
			assertThat(imagesCaptor.getValue()).hasSize(5);
		}

		@Test
		@DisplayName("이미지 1장으로 상품을 등록하면 그 이미지가 대표가 된다")
		void createsProductWithOneImage() {
			ProductCreateRequest request = createRequest(
					"아이폰 15",
					BigDecimal.valueOf(800000),
					List.of("https://example.com/only.jpg"),
					0
			);
			given(memberRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
			given(categoryRepository.findById(request.getCategoryId())).willReturn(Optional.of(category("디지털기기")));
			given(regionRepository.findByCode(request.getRegionCode())).willReturn(Optional.of(regionByCode(request.getRegionCode())));
			given(productRepository.save(any(Product.class))).willAnswer(invocation -> invocation.getArgument(0));

			ProductResponse response = productService.createProduct(SELLER_ID, request);

			ArgumentCaptor<List<ProductImage>> imagesCaptor = imageListCaptor();
			verify(productImageRepository).saveAll(imagesCaptor.capture());
			assertThat(imagesCaptor.getValue()).extracting(ProductImage::isRepresentative)
					.containsExactly(true);
			assertThat(response.getThumbnailUrl()).isEqualTo("https://example.com/only.jpg");
		}

		@Test
		@DisplayName("대표 인덱스가 마지막 이미지이면 마지막 이미지를 대표로 등록한다")
		void createsProductWithLastThumbnailIndex() {
			ProductCreateRequest request = createRequest(
					"아이폰 15",
					BigDecimal.valueOf(800000),
					List.of("https://example.com/1.jpg", "https://example.com/2.jpg", "https://example.com/3.jpg"),
					2
			);
			given(memberRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
			given(categoryRepository.findById(request.getCategoryId())).willReturn(Optional.of(category("디지털기기")));
			given(regionRepository.findByCode(request.getRegionCode())).willReturn(Optional.of(regionByCode(request.getRegionCode())));
			given(productRepository.save(any(Product.class))).willAnswer(invocation -> invocation.getArgument(0));

			ProductResponse response = productService.createProduct(SELLER_ID, request);

			assertThat(response.getThumbnailUrl()).isEqualTo("https://example.com/3.jpg");
		}

		@Test
		@DisplayName("이미지 목록이 null이면 상품을 등록할 수 없다")
		void throwsInvalidInputWhenImageUrlsAreNull() {
			ProductCreateRequest request = createRequest("아이폰 15", BigDecimal.valueOf(800000), null, 0);

			assertBusinessException(
					() -> productService.createProduct(SELLER_ID, request),
					ErrorCode.INVALID_INPUT_VALUE
			);
		}

		@Test
		@DisplayName("이미지 목록이 비어 있으면 상품을 등록할 수 없다")
		void throwsInvalidInputWhenImageUrlsAreEmpty() {
			ProductCreateRequest request = createRequest("아이폰 15", BigDecimal.valueOf(800000), List.of(), 0);

			assertBusinessException(
					() -> productService.createProduct(SELLER_ID, request),
					ErrorCode.INVALID_INPUT_VALUE
			);
		}

		@Test
		@DisplayName("이미지가 6장이면 상품을 등록할 수 없다")
		void throwsInvalidInputWhenImageUrlsSizeIsGreaterThanFive() {
			ProductCreateRequest request = createRequest(
					"아이폰 15",
					BigDecimal.valueOf(800000),
					List.of(
							"https://example.com/1.jpg",
							"https://example.com/2.jpg",
							"https://example.com/3.jpg",
							"https://example.com/4.jpg",
							"https://example.com/5.jpg",
							"https://example.com/6.jpg"
					),
					0
			);

			assertBusinessException(
					() -> productService.createProduct(SELLER_ID, request),
					ErrorCode.INVALID_INPUT_VALUE
			);
		}

		@Test
		@DisplayName("이미지 URL이 공백이면 상품을 등록할 수 없다")
		void throwsInvalidInputWhenImageUrlIsBlank() {
			ProductCreateRequest request = createRequest(
					"아이폰 15",
					BigDecimal.valueOf(800000),
					List.of("https://example.com/1.jpg", " "),
					0
			);

			assertBusinessException(
					() -> productService.createProduct(SELLER_ID, request),
					ErrorCode.INVALID_INPUT_VALUE
			);
		}

		@Test
		@DisplayName("대표 인덱스가 음수이면 상품을 등록할 수 없다")
		void throwsInvalidInputWhenThumbnailIndexIsNegative() {
			ProductCreateRequest request = createRequest(
					"아이폰 15",
					BigDecimal.valueOf(800000),
					List.of("https://example.com/1.jpg"),
					-1
			);

			assertBusinessException(
					() -> productService.createProduct(SELLER_ID, request),
					ErrorCode.INVALID_INPUT_VALUE
			);
		}

		@Test
		@DisplayName("대표 인덱스가 이미지 크기와 같으면 상품을 등록할 수 없다")
		void throwsInvalidInputWhenThumbnailIndexIsSameAsImageSize() {
			ProductCreateRequest request = createRequest(
					"아이폰 15",
					BigDecimal.valueOf(800000),
					List.of("https://example.com/1.jpg"),
					1
			);

			assertBusinessException(
					() -> productService.createProduct(SELLER_ID, request),
					ErrorCode.INVALID_INPUT_VALUE
			);
		}

		@Test
		@DisplayName("이미지 없는 기존 상품을 상세 조회하면 썸네일은 null이고 이미지 목록은 빈 배열이다")
		void getsExistingProductWithoutImages() {
			Product product = product("이미지 없는 상품", BigDecimal.valueOf(10000));
			given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
			given(productImageRepository.findAllByProductIdOrderBySortOrderAsc(PRODUCT_ID)).willReturn(List.of());

			ProductResponse response = productService.getProduct(PRODUCT_ID);

			assertThat(response.getThumbnailUrl()).isNull();
			assertThat(response.getImageUrls()).isEmpty();
		}

		@Test
		@DisplayName("등록 지역이 지역 마스터에 없으면 상품을 등록할 수 없다")
		void throwsInvalidInputWhenCreatingWithUnknownRegion() {
			ProductCreateRequest request = createRequest(
					"아이폰 15",
					BigDecimal.valueOf(800000),
					List.of("https://example.com/default.jpg"),
					0,
					"강남"
			);
			given(memberRepository.findById(SELLER_ID)).willReturn(Optional.of(seller()));
			given(categoryRepository.findById(request.getCategoryId())).willReturn(Optional.of(category("디지털기기")));
			given(regionRepository.findByCode(request.getRegionCode())).willReturn(Optional.empty());

			assertBusinessException(
					() -> productService.createProduct(SELLER_ID, request),
					ErrorCode.INVALID_INPUT_VALUE
			);

			verify(productRepository, never()).save(any());
		}
	}

	private Member seller() {
		return member(SELLER_ID, "seller@example.com", "판매자");
	}

	private Member lowTrustSeller() {
		return member(LOW_TRUST_SELLER_ID, "low-trust-seller@example.com", "저신뢰판매자");
	}

	private Member member(Long id, String email, String nickname) {
		Member member = Member.createUser(email, "encodedPassword", nickname);
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private Category category(String name) {
		return new Category(name);
	}

	private Region dongRegion() {
		return Region.child(
				"1168010100",
				3,
				Region.child("1168000000", 2, Region.root("1100000000", "서울특별시", "서울특별시"), "서울특별시 강남구", "강남구"),
				"서울특별시 강남구 역삼동",
				"역삼동"
		);
	}

	private Region updateDongRegion() {
		return Region.child(
				"1165010800",
				3,
				Region.child("1165000000", 2, Region.root("1100000000", "서울특별시", "서울특별시"), "서울특별시 서초구", "서초구"),
				"서울특별시 서초구 서초동",
				"서초동"
		);
	}

	private Region regionByCode(String regionCode) {
		if ("1165010800".equals(regionCode)) {
			return updateDongRegion();
		}
		return dongRegion();
	}

	private Product product(String title, BigDecimal price) {
		return product(null, title, price);
	}

	private Product product(Long id, String title, BigDecimal price) {
		return product(id, title, price, seller());
	}

	private Product product(Long id, String title, BigDecimal price, Member seller) {
		Product product = Product.create(
				seller,
				category("디지털기기"),
				title,
				"상품 설명입니다.",
				price,
				dongRegion()
		);
		if (id != null) {
			ReflectionTestUtils.setField(product, "id", id);
		}
		return product;
	}

	private Product hiddenProduct(String title) {
		Product product = product(title, BigDecimal.valueOf(800000));
		product.hide();
		return product;
	}

	private Product deletedProduct(String title) {
		Product product = product(title, BigDecimal.valueOf(800000));
		product.softDelete();
		return product;
	}

		private Product completedProduct(String title) {
			Product product = product(title, BigDecimal.valueOf(800000));
			product.complete();
			return product;
		}

		private Product productBySellerStatus(String title, MemberStatus status) {
			Member seller = seller();
			seller.changeStatus(status);
			return Product.create(
					seller,
					category("디지털기기"),
					title,
					"상품 설명입니다.",
					BigDecimal.valueOf(800000),
					dongRegion()
			);
		}

	private ProductCreateRequest createRequest(String title, BigDecimal price) {
		return createRequest(
				title,
				price,
				List.of("https://example.com/default.jpg"),
				0
		);
	}

	private ProductCreateRequest createRequest(String title, BigDecimal price, List<String> imageUrls, int thumbnailIndex) {
		return createRequest(title, price, imageUrls, thumbnailIndex, "1168010100");
	}

	private ProductCreateRequest createRequest(String title, BigDecimal price, List<String> imageUrls,
											   int thumbnailIndex, String regionCode) {
		return new ProductCreateRequest(
				CATEGORY_ID,
				title,
				"상태 좋은 아이폰입니다.",
				price,
				regionCode,
				imageUrls,
				thumbnailIndex
		);
	}

	private ProductUpdateRequest updateRequest(String title, BigDecimal price) {
		return updateRequest(
				title,
				price,
				List.of("https://example.com/update-default.jpg"),
				0
		);
	}

	private ProductUpdateRequest updateRequest(String title, BigDecimal price, List<String> imageUrls, int thumbnailIndex) {
		return updateRequest(title, price, imageUrls, thumbnailIndex, "1165010800");
	}

	private ProductUpdateRequest updateRequest(String title, BigDecimal price, List<String> imageUrls,
											   int thumbnailIndex, String regionCode) {
		return new ProductUpdateRequest(
				UPDATE_CATEGORY_ID,
				title,
				"수정된 상품 설명입니다.",
				price,
				regionCode,
				imageUrls,
				thumbnailIndex
		);
	}

	private ProductStatusUpdateRequest statusRequest(String status) {
		return new ProductStatusUpdateRequest(status);
	}

	private void assertInvalidSearchCondition(ProductSearchRequest request) {
		assertBusinessException(
				() -> productService.searchProducts(request),
				ErrorCode.INVALID_SEARCH_CONDITION
		);

		verify(productRepository, never()).findAll(anyProductSpecification(), any(Sort.class));
	}

	private void assertInvalidTradeStatus(ThrowingCallable callable) {
		assertBusinessException(callable, ErrorCode.INVALID_TRADE_STATUS);

		verify(productRepository, never()).findById(any());
	}

	private void assertInaccessibleProduct() {
		assertBusinessException(
				() -> productService.validateAccessibleProduct(PRODUCT_ID),
				ErrorCode.PRODUCT_NOT_FOUND
		);
	}

	private void assertBusinessException(ThrowingCallable callable, ErrorCode errorCode) {
		assertThatThrownBy(callable)
				.isInstanceOf(BusinessException.class)
				.hasFieldOrPropertyWithValue("errorCode", errorCode);
	}

	private Specification<Product> anyProductSpecification() {
		return org.mockito.ArgumentMatchers.any();
	}

	@SuppressWarnings("unchecked")
	private ArgumentCaptor<List<ProductImage>> imageListCaptor() {
		return ArgumentCaptor.forClass(List.class);
	}
}
