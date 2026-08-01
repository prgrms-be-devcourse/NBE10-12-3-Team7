package com.dongnemarket.product.repository;

import java.math.BigDecimal;

import com.dongnemarket.category.entity.Category;
import com.dongnemarket.category.repository.CategoryRepository;
import com.dongnemarket.global.config.JpaAuditingConfig;
import com.dongnemarket.member.entity.Member;
import com.dongnemarket.member.entity.MemberStatus;
import com.dongnemarket.member.repository.MemberRepository;
import com.dongnemarket.product.entity.Product;
import com.dongnemarket.product.entity.TradeStatus;
import com.dongnemarket.product.repository.spec.ProductSpecification;
import com.dongnemarket.region.entity.Region;
import com.dongnemarket.region.repository.RegionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:product_repository_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class ProductRepositoryTest {

	@Autowired
	ProductRepository productRepository;

	@Autowired
	MemberRepository memberRepository;

	@Autowired
	CategoryRepository categoryRepository;

	@Autowired
	RegionRepository regionRepository;

	@Autowired
	EntityManager entityManager;

	@Test
	@DisplayName("상품을 저장하고 기본 필드와 시간 필드를 조회할 수 있다")
	void savesProductWithBaseFields() {
		Member member = memberRepository.save(Member.createUser("seller@example.com", "encodedPassword", "판매자"));
		Category category = categoryRepository.save(new Category("디지털기기"));

		Product product = Product.create(
				member,
				category,
				"아이폰 15",
				"상태 좋은 아이폰입니다.",
				BigDecimal.valueOf(800000),
				saveGangnamWithDong()
		);

		Product savedProduct = productRepository.saveAndFlush(product);

		assertThat(savedProduct.getId()).isNotNull();
		assertThat(savedProduct.getMember().getId()).isEqualTo(member.getId());
		assertThat(savedProduct.getCategory().getId()).isEqualTo(category.getId());
		assertThat(savedProduct.getTitle()).isEqualTo("아이폰 15");
		assertThat(savedProduct.getDescription()).isEqualTo("상태 좋은 아이폰입니다.");
		assertThat(savedProduct.getPrice()).isEqualByComparingTo("800000");
		assertThat(savedProduct.getTradeStatus()).isEqualTo(TradeStatus.ON_SALE);
		assertThat(savedProduct.getRegionCode()).isEqualTo("1168010100");
		assertThat(savedProduct.getRegionFullName()).isEqualTo("서울특별시 강남구 역삼동");
		assertThat(savedProduct.getViewCount()).isZero();
		assertThat(savedProduct.isHidden()).isFalse();
		assertThat(savedProduct.getDeletedAt()).isNull();
		assertThat(savedProduct.getCreatedAt()).isNotNull();
		assertThat(savedProduct.getUpdatedAt()).isNotNull();
	}

	@Test
	@DisplayName("삭제되지 않고 숨김 처리되지 않은 상품이면 접근 가능한 상품으로 판단한다")
	void returnsTrueWhenProductIsAccessible() {
		Member member = memberRepository.save(Member.createUser("seller-accessible@example.com", "encodedPassword", "판매자"));
		Category category = categoryRepository.save(new Category("생활가전"));
		Product product = productRepository.saveAndFlush(Product.create(
				member,
				category,
				"공기청정기",
				"상태 좋은 공기청정기입니다.",
				BigDecimal.valueOf(120000),
				saveGangnamWithDong()
		));

		boolean exists = productRepository.existsByIdAndDeletedAtIsNullAndIsHiddenFalse(product.getId());

		assertThat(exists).isTrue();
	}

	@Test
	@DisplayName("상품 저장 시 favoriteCount 기본값은 0이다")
	void savesProductWithDefaultFavoriteCount() {
		Member member = memberRepository.save(Member.createUser("favorite-default@example.com", "encodedPassword", "판매자"));
		Category category = categoryRepository.save(new Category("디지털기기"));
		Product product = productRepository.saveAndFlush(Product.create(
				member,
				category,
				"아이패드",
				"깨끗한 아이패드입니다.",
				BigDecimal.valueOf(500000),
				saveGangnamWithDong()
		));

		assertThat(product.getFavoriteCount()).isZero();
	}

	@Test
	@DisplayName("favoriteCount는 원자 UPDATE로 1 증가한다")
	void incrementsFavoriteCount() {
		Member member = memberRepository.save(Member.createUser("favorite-increment@example.com", "encodedPassword", "판매자"));
		Category category = categoryRepository.save(new Category("생활가전"));
		Product product = productRepository.saveAndFlush(Product.create(
				member,
				category,
				"청소기",
				"상태 좋은 청소기입니다.",
				BigDecimal.valueOf(150000),
				saveSeochoWithDong()
		));

		productRepository.incrementFavoriteCount(product.getId());
		productRepository.flush();
		entityManager.clear();

		Product foundProduct = productRepository.findById(product.getId()).orElseThrow();
		assertThat(foundProduct.getFavoriteCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("favoriteCount는 원자 UPDATE로 1 감소한다")
	void decrementsFavoriteCount() {
		Member member = memberRepository.save(Member.createUser("favorite-decrement@example.com", "encodedPassword", "판매자"));
		Category category = categoryRepository.save(new Category("가구/인테리어"));
		Product product = productRepository.saveAndFlush(Product.create(
				member,
				category,
				"책상",
				"튼튼한 책상입니다.",
				BigDecimal.valueOf(70000),
				saveSongpaWithDong()
		));
		productRepository.incrementFavoriteCount(product.getId());
		productRepository.flush();
		entityManager.clear();

		productRepository.decrementFavoriteCount(product.getId());
		productRepository.flush();
		entityManager.clear();

		Product foundProduct = productRepository.findById(product.getId()).orElseThrow();
		assertThat(foundProduct.getFavoriteCount()).isZero();
	}

	@Test
	@DisplayName("favoriteCount가 0이면 감소 요청을 해도 음수가 되지 않는다")
	void doesNotDecrementFavoriteCountBelowZero() {
		Member member = memberRepository.save(Member.createUser("favorite-zero@example.com", "encodedPassword", "판매자"));
		Category category = categoryRepository.save(new Category("도서"));
		Product product = productRepository.saveAndFlush(Product.create(
				member,
				category,
				"자바 책",
				"깨끗한 자바 책입니다.",
				BigDecimal.valueOf(20000),
				saveMapoWithDong()
		));

		productRepository.decrementFavoriteCount(product.getId());
		productRepository.flush();
		entityManager.clear();

		Product foundProduct = productRepository.findById(product.getId()).orElseThrow();
		assertThat(foundProduct.getFavoriteCount()).isZero();
	}

	@Nested
	@DisplayName("관심 수 원자 업데이트")
	class FavoriteCountAtomicUpdate {

		@Test
		@DisplayName("존재하지 않는 상품의 관심 수 증가를 요청해도 예외가 발생하지 않는다")
		void doesNotThrowWhenIncrementingMissingProduct() {
			Long missingProductId = Long.MAX_VALUE;

			assertThatCode(() -> productRepository.incrementFavoriteCount(missingProductId))
					.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("존재하지 않는 상품의 관심 수 감소를 요청해도 예외가 발생하지 않는다")
		void doesNotThrowWhenDecrementingMissingProduct() {
			Long missingProductId = Long.MAX_VALUE;

			assertThatCode(() -> productRepository.decrementFavoriteCount(missingProductId))
					.doesNotThrowAnyException();
		}

		@Test
		@DisplayName("특정 상품의 관심 수만 증가하고 다른 상품은 변경되지 않는다")
		void incrementsOnlyTargetProductFavoriteCount() {
			Member member = memberRepository.save(Member.createUser("favorite-target@example.com", "encodedPassword", "판매자"));
			Category category = categoryRepository.save(new Category("반려동물용품"));
			Product targetProduct = saveFavoriteCountProduct(member, category, "관심 증가 대상 상품");
			Product otherProduct = saveFavoriteCountProduct(member, category, "관심 증가 비대상 상품");

			productRepository.incrementFavoriteCount(targetProduct.getId());
			productRepository.flush();
			entityManager.clear();

			Product foundTargetProduct = productRepository.findById(targetProduct.getId()).orElseThrow();
			Product foundOtherProduct = productRepository.findById(otherProduct.getId()).orElseThrow();
			assertThat(foundTargetProduct.getFavoriteCount()).isEqualTo(1);
			assertThat(foundOtherProduct.getFavoriteCount()).isZero();
		}

		@Test
		@DisplayName("관심 수 증가와 감소를 여러 번 호출하면 최종 값이 정확히 반영된다")
		void reflectsFinalFavoriteCountAfterRepeatedIncrementAndDecrement() {
			Member member = memberRepository.save(Member.createUser("favorite-repeated@example.com", "encodedPassword", "판매자"));
			Category category = categoryRepository.save(new Category("기타"));
			Product product = saveFavoriteCountProduct(member, category, "관심 반복 변경 상품");

			productRepository.incrementFavoriteCount(product.getId());
			productRepository.incrementFavoriteCount(product.getId());
			productRepository.incrementFavoriteCount(product.getId());
			productRepository.decrementFavoriteCount(product.getId());
			productRepository.flush();
			entityManager.clear();

			Product foundProduct = productRepository.findById(product.getId()).orElseThrow();
			assertThat(foundProduct.getFavoriteCount()).isEqualTo(2);
		}

		private Product saveFavoriteCountProduct(Member member, Category category, String title) {
			return productRepository.saveAndFlush(Product.create(
					member,
					category,
					title,
					"관심 수 원자 업데이트 테스트 상품입니다.",
					BigDecimal.valueOf(10000),
					saveGangnamWithDong()
			));
		}
	}

	@Test
	@DisplayName("삭제된 상품이면 접근 가능한 상품으로 판단하지 않는다")
	void returnsFalseWhenProductIsDeleted() {
		Member member = memberRepository.save(Member.createUser("seller-deleted@example.com", "encodedPassword", "판매자"));
		Category category = categoryRepository.save(new Category("가구/인테리어"));
		Product product = Product.create(
				member,
				category,
				"의자",
				"사용감 있는 의자입니다.",
				BigDecimal.valueOf(30000),
				saveSeochoWithDong()
		);
		product.softDelete();
		Product savedProduct = productRepository.saveAndFlush(product);

		boolean exists = productRepository.existsByIdAndDeletedAtIsNullAndIsHiddenFalse(savedProduct.getId());

		assertThat(exists).isFalse();
	}

	@Test
	@DisplayName("숨김 상품이면 접근 가능한 상품으로 판단하지 않는다")
	void returnsFalseWhenProductIsHidden() {
		Member member = memberRepository.save(Member.createUser("seller-hidden@example.com", "encodedPassword", "판매자"));
		Category category = categoryRepository.save(new Category("도서"));
		Product product = Product.create(
				member,
				category,
				"자바 책",
				"깨끗한 자바 책입니다.",
				BigDecimal.valueOf(15000),
				saveSongpaWithDong()
		);
		product.hide();
		Product savedProduct = productRepository.saveAndFlush(product);

		boolean exists = productRepository.existsByIdAndDeletedAtIsNullAndIsHiddenFalse(savedProduct.getId());

		assertThat(exists).isFalse();
	}

	@Test
	@DisplayName("카테고리별 상품은 최신 등록순으로 조회하고 숨김·삭제 상품은 제외한다")
	void findsProductsByCategoryInLatestOrder() {
		Member member = memberRepository.save(Member.createUser("category-seller@example.com", "encodedPassword", "판매자"));
		Category targetCategory = categoryRepository.save(new Category("생활가전"));
		Category otherCategory = categoryRepository.save(new Category("도서"));
		Product oldProduct = productRepository.save(Product.create(
				member,
				targetCategory,
				"오래된 생활가전",
				"오래된 생활가전 설명",
				BigDecimal.valueOf(10000),
				saveGangnamWithDong()
		));
		Product newProduct = productRepository.save(Product.create(
				member,
				targetCategory,
				"최신 생활가전",
				"최신 생활가전 설명",
				BigDecimal.valueOf(20000),
				saveSeochoWithDong()
		));
		productRepository.save(Product.create(
				member,
				otherCategory,
				"다른 카테고리 상품",
				"다른 카테고리 상품 설명",
				BigDecimal.valueOf(30000),
				saveSongpaWithDong()
		));
		Product hiddenProduct = Product.create(member, targetCategory, "숨김 상품", "숨김 상품 설명", BigDecimal.valueOf(40000), saveMapoWithDong());
		hiddenProduct.hide();
		productRepository.save(hiddenProduct);
		Product deletedProduct = Product.create(member, targetCategory, "삭제 상품", "삭제 상품 설명", BigDecimal.valueOf(50000), saveYongsanWithDong());
		deletedProduct.softDelete();
		productRepository.saveAndFlush(deletedProduct);

		List<Product> products = productRepository.findAll(
				ProductSpecification.categoryList(targetCategory.getId()),
				Sort.by(Sort.Direction.DESC, "id")
		);

		assertThat(products).containsExactly(newProduct, oldProduct);
	}

	@Test
	@DisplayName("내 상품 목록은 최신 등록순으로 조회하고 숨김 상품은 포함하며 삭제 상품은 제외한다")
	void findsMyProductsInLatestOrderIncludingHiddenProducts() {
		Member member = memberRepository.save(Member.createUser("my-seller@example.com", "encodedPassword", "판매자"));
		Member otherMember = memberRepository.save(Member.createUser("other-seller@example.com", "encodedPassword", "다른판매자"));
		Category category = categoryRepository.save(new Category("스포츠/레저"));
		Product oldProduct = productRepository.save(Product.create(
				member,
				category,
				"오래된 내 상품",
				"오래된 내 상품 설명",
				BigDecimal.valueOf(10000),
				saveGangnamWithDong()
		));
		Product hiddenProduct = Product.create(member, category, "숨김 내 상품", "숨김 내 상품 설명", BigDecimal.valueOf(20000), saveSeochoWithDong());
		hiddenProduct.hide();
		Product savedHiddenProduct = productRepository.save(hiddenProduct);
		productRepository.save(Product.create(
				otherMember,
				category,
				"다른 회원 상품",
				"다른 회원 상품 설명",
				BigDecimal.valueOf(30000),
				saveSongpaWithDong()
		));
		Product deletedProduct = Product.create(member, category, "삭제 내 상품", "삭제 내 상품 설명", BigDecimal.valueOf(40000), saveMapoWithDong());
		deletedProduct.softDelete();
		productRepository.saveAndFlush(deletedProduct);

		List<Product> products = productRepository.findAllByMemberIdAndDeletedAtIsNullOrderByIdDesc(member.getId());

		assertThat(products).containsExactly(savedHiddenProduct, oldProduct);
	}

	@Test
	@DisplayName("상품 검색은 키워드로 제목과 설명을 검색하고 숨김·삭제 상품은 제외한다")
	void searchesProductsByKeywordExcludingHiddenAndDeletedProducts() {
		Member member = memberRepository.save(Member.createUser("search-seller@example.com", "encodedPassword", "판매자"));
		Category category = categoryRepository.save(new Category("디지털기기"));
		Product titleMatchedProduct = productRepository.save(Product.create(
				member,
				category,
				"맥북 프로",
				"상태 좋은 노트북입니다.",
				BigDecimal.valueOf(1200000),
				saveGangnamWithDong()
		));
		Product descriptionMatchedProduct = productRepository.save(Product.create(
				member,
				category,
				"노트북 거치대",
				"맥북과 함께 쓰기 좋습니다.",
				BigDecimal.valueOf(30000),
				saveSeochoWithDong()
		));
		Product hiddenProduct = Product.create(member, category, "숨김 맥북", "숨김 상품입니다.", BigDecimal.valueOf(900000), saveSongpaWithDong());
		hiddenProduct.hide();
		productRepository.save(hiddenProduct);
		Product deletedProduct = Product.create(member, category, "삭제 맥북", "삭제 상품입니다.", BigDecimal.valueOf(800000), saveMapoWithDong());
		deletedProduct.softDelete();
		productRepository.saveAndFlush(deletedProduct);

		List<Product> products = productRepository.findAll(
				ProductSpecification.search("맥북", null, (BigDecimal) null, null, null, null),
				Sort.by(Sort.Direction.DESC, "id")
		);

		assertThat(products).containsExactly(descriptionMatchedProduct, titleMatchedProduct);
	}

	@Test
	@DisplayName("상품 검색은 카테고리, 가격 범위, 거래 상태를 함께 필터링한다")
	void searchesProductsByCategoryPriceRangeAndTradeStatus() {
		Member member = memberRepository.save(Member.createUser("filter-seller@example.com", "encodedPassword", "판매자"));
		Category targetCategory = categoryRepository.save(new Category("생활가전"));
		Category otherCategory = categoryRepository.save(new Category("도서"));
		Product targetProduct = Product.create(
				member,
				targetCategory,
				"예약 중인 청소기",
				"상태 좋은 청소기입니다.",
				BigDecimal.valueOf(150000),
				saveGangnamWithDong()
		);
		targetProduct.changeTradeStatus(TradeStatus.RESERVED);
		Product savedTargetProduct = productRepository.save(targetProduct);
		Product wrongStatusProduct = productRepository.save(Product.create(
				member,
				targetCategory,
				"판매 중인 청소기",
				"상태 좋은 청소기입니다.",
				BigDecimal.valueOf(160000),
				saveSeochoWithDong()
		));
		Product wrongCategoryProduct = Product.create(member, otherCategory, "예약 중인 책", "청소기 설명이 있는 책입니다.", BigDecimal.valueOf(150000), saveSongpaWithDong());
		wrongCategoryProduct.changeTradeStatus(TradeStatus.RESERVED);
		productRepository.save(wrongCategoryProduct);
		Product wrongPriceProduct = Product.create(member, targetCategory, "비싼 청소기", "비싼 청소기입니다.", BigDecimal.valueOf(500000), saveMapoWithDong());
		wrongPriceProduct.changeTradeStatus(TradeStatus.RESERVED);
		productRepository.saveAndFlush(wrongPriceProduct);

		List<Product> products = productRepository.findAll(
				ProductSpecification.search(
						"청소기",
						targetCategory.getId(),
						BigDecimal.valueOf(100000),
						BigDecimal.valueOf(200000),
						TradeStatus.RESERVED,
						null
				),
				Sort.by(Sort.Direction.DESC, "id")
		);

		assertThat(products).containsExactly(savedTargetProduct);
		assertThat(products).doesNotContain(wrongStatusProduct, wrongCategoryProduct, wrongPriceProduct);
	}

	@Test
	@DisplayName("상품 목록 조건은 지역 필터가 없어도 기존처럼 숨김·삭제 상품을 제외하고 최신순으로 조회한다")
	void findsProductsWithoutRegionFilterKeepingExistingListBehavior() {
		Member member = memberRepository.save(Member.createUser("region-list-regression@example.com", "encodedPassword", "판매자"));
		Category category = categoryRepository.save(new Category("지역목록회귀"));
		Product oldProduct = productRepository.save(Product.create(
				member,
				category,
				"오래된 상품",
				"오래된 상품 설명",
				BigDecimal.valueOf(10000),
				saveGangnamWithDong()
		));
		Product newProduct = productRepository.save(Product.create(
				member,
				category,
				"최신 상품",
				"최신 상품 설명",
				BigDecimal.valueOf(20000),
				saveMapoWithDong()
		));
		Product hiddenProduct = Product.create(member, category, "숨김 상품", "숨김 상품 설명", BigDecimal.valueOf(30000), saveSeochoWithDong());
		hiddenProduct.hide();
		productRepository.save(hiddenProduct);
		Product deletedProduct = Product.create(member, category, "삭제 상품", "삭제 상품 설명", BigDecimal.valueOf(40000), saveSongpaWithDong());
		deletedProduct.softDelete();
		productRepository.saveAndFlush(deletedProduct);

			List<Product> products = productRepository.findAll(
					ProductSpecification.list(null, null),
					Sort.by(Sort.Direction.DESC, "id")
			);

		assertThat(products).containsExactly(newProduct, oldProduct);
		assertThat(products).doesNotContain(hiddenProduct, deletedProduct);
	}

	@Test
	@DisplayName("상품 목록 조건은 탈퇴·정지 판매자 상품과 거래완료 상품을 제외한다")
	void excludesInactiveSellerProductsAndCompletedProductsFromList() {
		Member activeMember = memberRepository.save(Member.createUser("visible-active@example.com", "encodedPassword", "활성판매자"));
		Member deletedMember = memberRepository.save(Member.createUser("visible-deleted@example.com", "encodedPassword", "탈퇴판매자"));
		deletedMember.changeStatus(MemberStatus.DELETED);
		Member suspendedMember = memberRepository.save(Member.createUser("visible-suspended@example.com", "encodedPassword", "정지판매자"));
		suspendedMember.changeStatus(MemberStatus.SUSPENDED);
		Category category = categoryRepository.save(new Category("공개목록정책"));
		Product onSaleProduct = productRepository.save(Product.create(
				activeMember,
				category,
				"판매중 공개 상품",
				"판매중 공개 상품 설명",
				BigDecimal.valueOf(10000),
				saveGangnamWithDong()
		));
		Product reservedProduct = Product.create(activeMember, category, "예약중 공개 상품", "예약중 공개 상품 설명", BigDecimal.valueOf(20000), saveMapoWithDong());
		reservedProduct.changeTradeStatus(TradeStatus.RESERVED);
		Product savedReservedProduct = productRepository.save(reservedProduct);
		Product completedProduct = Product.create(activeMember, category, "거래완료 상품", "거래완료 상품 설명", BigDecimal.valueOf(30000), saveSeochoWithDong());
		completedProduct.complete();
		productRepository.save(completedProduct);
		Product deletedSellerProduct = productRepository.save(Product.create(
				deletedMember,
				category,
				"탈퇴 판매자 상품",
				"탈퇴 판매자 상품 설명",
				BigDecimal.valueOf(40000),
				saveSongpaWithDong()
		));
		Product suspendedSellerProduct = productRepository.saveAndFlush(Product.create(
				suspendedMember,
				category,
				"정지 판매자 상품",
				"정지 판매자 상품 설명",
				BigDecimal.valueOf(50000),
				saveYongsanWithDong()
		));

		List<Product> products = productRepository.findAll(
				ProductSpecification.list(null, null),
				Sort.by(Sort.Direction.DESC, "id")
		);

		assertThat(products).containsExactly(savedReservedProduct, onSaleProduct);
		assertThat(products).doesNotContain(completedProduct, deletedSellerProduct, suspendedSellerProduct);
	}

	@Test
	@DisplayName("상품 목록 조건은 시군구 regionCode를 지정하면 하위 동 상품을 조회한다")
	void findsProductsBySigunguRegionCode() {
		Member member = memberRepository.save(Member.createUser("region-one@example.com", "encodedPassword", "판매자"));
		Category category = categoryRepository.save(new Category("지역필터1"));
		Region gangnam = saveGangnam();
		Region yeoksam = saveDong(gangnam, "1168010100", "서울특별시 강남구 역삼동", "역삼동");
		Region daechi = saveDong(gangnam, "1168010600", "서울특별시 강남구 대치동", "대치동");
		Region songpa = saveSongpaWithDong();
		Product gangnamProduct = productRepository.save(Product.create(
				member,
				category,
				"역삼 상품",
				"역삼 상품 설명",
				BigDecimal.valueOf(10000),
				yeoksam
		));
		Product daechiProduct = productRepository.save(Product.create(
				member,
				category,
				"대치 상품",
				"대치 상품 설명",
				BigDecimal.valueOf(20000),
				daechi
		));
		Product songpaProduct = productRepository.saveAndFlush(Product.create(
				member,
				category,
				"송파 상품",
				"송파 상품 설명",
				BigDecimal.valueOf(30000),
				songpa
		));

				List<Product> products = productRepository.findAll(
						ProductSpecification.list(List.of("1168000000"), null),
						Sort.by(Sort.Direction.DESC, "id")
				);

		assertThat(products).containsExactly(daechiProduct, gangnamProduct);
		assertThat(products).doesNotContain(songpaProduct);
	}

	@Test
	@DisplayName("상품 목록 조건은 regionCode 2개를 지정하면 두 지역 상품을 최신순으로 조회한다")
	void findsProductsByTwoRegionCodesInLatestOrder() {
		Member member = memberRepository.save(Member.createUser("region-two@example.com", "encodedPassword", "판매자"));
		Category category = categoryRepository.save(new Category("지역필터2"));
		Region gangnam = saveGangnam();
		Region yeoksam = saveDong(gangnam, "1168010100", "서울특별시 강남구 역삼동", "역삼동");
		Region mapo = saveMapoWithDong();
		Region songpa = saveSongpaWithDong();
		Product gangnamProduct = productRepository.save(Product.create(
				member,
				category,
				"강남 상품",
				"강남 상품 설명",
				BigDecimal.valueOf(10000),
				yeoksam
		));
		Product mapoProduct = productRepository.save(Product.create(
				member,
				category,
				"마포 상품",
				"마포 상품 설명",
				BigDecimal.valueOf(20000),
				mapo
		));
		Product otherProduct = productRepository.saveAndFlush(Product.create(
				member,
				category,
				"송파 상품",
				"송파 상품 설명",
				BigDecimal.valueOf(30000),
				songpa
		));

				List<Product> products = productRepository.findAll(
						ProductSpecification.list(List.of("1168000000", "1144000000"), null),
						Sort.by(Sort.Direction.DESC, "id")
				);

		assertThat(products).containsExactly(mapoProduct, gangnamProduct);
		assertThat(products).doesNotContain(otherProduct);
	}

	@Test
	@DisplayName("상품 목록 조건은 존재하지 않는 지역을 지정하면 빈 목록을 반환한다")
	void returnsEmptyListWhenFilteringByUnknownRegionCode() {
		Member member = memberRepository.save(Member.createUser("region-unknown@example.com", "encodedPassword", "판매자"));
		Category category = categoryRepository.save(new Category("지역필터없음"));
		Region gangnam = saveGangnam();
		Region yeoksam = saveDong(gangnam, "1168010100", "서울특별시 강남구 역삼동", "역삼동");
		productRepository.saveAndFlush(Product.create(
				member,
				category,
				"강남 상품",
				"강남 상품 설명",
				BigDecimal.valueOf(10000),
				yeoksam
		));

				List<Product> products = productRepository.findAll(
						ProductSpecification.list(List.of("9999999999"), null),
						Sort.by(Sort.Direction.DESC, "id")
				);

			assertThat(products).isEmpty();
		}

		@Test
		@DisplayName("상품 목록 조건은 커서가 있으면 커서보다 작은 id의 상품만 조회한다")
		void findsProductsBeforeCursor() {
			Member member = memberRepository.save(Member.createUser("cursor-repository@example.com", "encodedPassword", "판매자"));
			Category category = categoryRepository.save(new Category("커서필터"));
			Product oldProduct = productRepository.save(Product.create(
					member,
					category,
					"오래된 상품",
					"오래된 상품 설명",
					BigDecimal.valueOf(10000),
					saveGangnamWithDong()
			));
			Product cursorProduct = productRepository.save(Product.create(
					member,
					category,
					"커서 상품",
					"커서 상품 설명",
					BigDecimal.valueOf(20000),
					saveGangnamWithDong()
			));
			Product newProduct = productRepository.saveAndFlush(Product.create(
					member,
					category,
					"최신 상품",
					"최신 상품 설명",
					BigDecimal.valueOf(30000),
					saveGangnamWithDong()
			));

			List<Product> products = productRepository.findAll(
					ProductSpecification.list(null, cursorProduct.getId()),
					Sort.by(Sort.Direction.DESC, "id")
			);

			assertThat(products).containsExactly(oldProduct);
			assertThat(products).doesNotContain(cursorProduct, newProduct);
		}

			@Test
			@DisplayName("상품 목록 조건은 지역 필터와 커서 조건을 함께 적용한다")
			void findsProductsByRegionCodesAndCursor() {
				Member member = memberRepository.save(Member.createUser("cursor-region-repository@example.com", "encodedPassword", "판매자"));
				Category category = categoryRepository.save(new Category("커서지역필터"));
				Region gangnam = saveGangnam();
				Region yeoksam = saveDong(gangnam, "1168010100", "서울특별시 강남구 역삼동", "역삼동");
				Region mapo = saveMapoWithDong();
				Product gangnamOldProduct = productRepository.save(Product.create(
						member,
						category,
						"강남 오래된 상품",
						"강남 오래된 상품 설명",
						BigDecimal.valueOf(10000),
						yeoksam
				));
				Product mapoProduct = productRepository.save(Product.create(
						member,
						category,
						"마포 상품",
						"마포 상품 설명",
						BigDecimal.valueOf(20000),
						mapo
				));
				Product gangnamCursorProduct = productRepository.save(Product.create(
						member,
						category,
						"강남 커서 상품",
						"강남 커서 상품 설명",
						BigDecimal.valueOf(30000),
						yeoksam
				));
				Product gangnamNewProduct = productRepository.saveAndFlush(Product.create(
						member,
						category,
						"강남 최신 상품",
						"강남 최신 상품 설명",
						BigDecimal.valueOf(40000),
						yeoksam
				));

				List<Product> products = productRepository.findAll(
						ProductSpecification.list(List.of("1168000000"), gangnamCursorProduct.getId()),
						Sort.by(Sort.Direction.DESC, "id")
				);

			assertThat(products).containsExactly(gangnamOldProduct);
			assertThat(products).doesNotContain(mapoProduct, gangnamCursorProduct, gangnamNewProduct);
		}

	@Test
		@DisplayName("상품 검색 조건은 키워드와 지역 필터를 함께 적용한다")
		void searchesProductsByKeywordAndRegions() {
			Member member = memberRepository.save(Member.createUser("region-search@example.com", "encodedPassword", "판매자"));
			Category category = categoryRepository.save(new Category("지역검색"));
			Region gangnam = saveGangnam();
			Region yeoksam = saveDong(gangnam, "1168010100", "서울특별시 강남구 역삼동", "역삼동");
			Region songpa = saveSongpaWithDong();
			Product matchedProduct = productRepository.save(Product.create(
					member,
					category,
					"맥북 프로",
					"상태 좋은 노트북입니다.",
					BigDecimal.valueOf(1200000),
					yeoksam
			));
			Product wrongRegionProduct = productRepository.save(Product.create(
					member,
					category,
					"맥북 에어",
					"가벼운 노트북입니다.",
					BigDecimal.valueOf(900000),
					songpa
			));
			Product wrongKeywordProduct = productRepository.saveAndFlush(Product.create(
					member,
					category,
					"아이패드",
					"상태 좋은 태블릿입니다.",
					BigDecimal.valueOf(700000),
					yeoksam
			));

			List<Product> products = productRepository.findAll(
					ProductSpecification.search("맥북", null, (BigDecimal) null, null, null, List.of("1168000000")),
					Sort.by(Sort.Direction.DESC, "id")
			);

		assertThat(products).containsExactly(matchedProduct);
		assertThat(products).doesNotContain(wrongRegionProduct, wrongKeywordProduct);
	}

	@Test
	@DisplayName("상품 검색 조건은 탈퇴·정지 판매자 상품과 거래완료 상품을 제외한다")
	void excludesInactiveSellerProductsAndCompletedProductsFromSearch() {
		Member activeMember = memberRepository.save(Member.createUser("search-visible-active@example.com", "encodedPassword", "활성검색판매자"));
		Member deletedMember = memberRepository.save(Member.createUser("search-visible-deleted@example.com", "encodedPassword", "탈퇴검색판매자"));
		deletedMember.changeStatus(MemberStatus.DELETED);
		Member suspendedMember = memberRepository.save(Member.createUser("search-visible-suspended@example.com", "encodedPassword", "정지검색판매자"));
		suspendedMember.changeStatus(MemberStatus.SUSPENDED);
		Category category = categoryRepository.save(new Category("공개검색정책"));
		Region gangnam = saveGangnam();
		Region yeoksam = saveDong(gangnam, "1168010100", "서울특별시 강남구 역삼동", "역삼동");
		Product visibleProduct = productRepository.save(Product.create(
				activeMember,
				category,
				"정책 맥북",
				"공개 검색 상품입니다.",
				BigDecimal.valueOf(1000000),
				yeoksam
		));
		Product completedProduct = Product.create(activeMember, category, "정책 완료 맥북", "거래완료 검색 상품입니다.", BigDecimal.valueOf(900000), yeoksam);
		completedProduct.complete();
		productRepository.save(completedProduct);
		Product deletedSellerProduct = productRepository.save(Product.create(
				deletedMember,
				category,
				"정책 탈퇴 맥북",
				"탈퇴 판매자 검색 상품입니다.",
				BigDecimal.valueOf(800000),
				yeoksam
		));
		Product suspendedSellerProduct = productRepository.saveAndFlush(Product.create(
				suspendedMember,
				category,
				"정책 정지 맥북",
				"정지 판매자 검색 상품입니다.",
				BigDecimal.valueOf(700000),
				yeoksam
		));

		List<Product> products = productRepository.findAll(
				ProductSpecification.search("정책", null, null, null, null, List.of("1168000000")),
				Sort.by(Sort.Direction.DESC, "id")
		);

		assertThat(products).containsExactly(visibleProduct);
		assertThat(products).doesNotContain(completedProduct, deletedSellerProduct, suspendedSellerProduct);
	}

	@Test
	@DisplayName("상품 검색 조건은 거래완료 상태를 요청하면 빈 목록을 반환한다")
	void returnsEmptyWhenSearchingCompletedProducts() {
		Member member = memberRepository.save(Member.createUser("search-completed-empty@example.com", "encodedPassword", "완료검색판매자"));
		Category category = categoryRepository.save(new Category("거래완료검색"));
		Product completedProduct = Product.create(
				member,
				category,
				"거래완료 맥북",
				"거래완료 검색 상품입니다.",
				BigDecimal.valueOf(1000000),
				saveGangnamWithDong()
		);
		completedProduct.complete();
		productRepository.saveAndFlush(completedProduct);

		List<Product> products = productRepository.findAll(
				ProductSpecification.search("맥북", null, null, null, TradeStatus.COMPLETED, null),
				Sort.by(Sort.Direction.DESC, "id")
		);

		assertThat(products).isEmpty();
	}

	@Test
	@DisplayName("카테고리별 상품 조건은 탈퇴·정지 판매자 상품과 거래완료 상품을 제외한다")
	void excludesInactiveSellerProductsAndCompletedProductsFromCategoryList() {
		Member activeMember = memberRepository.save(Member.createUser("category-visible-active@example.com", "encodedPassword", "활성카테고리판매자"));
		Member deletedMember = memberRepository.save(Member.createUser("category-visible-deleted@example.com", "encodedPassword", "탈퇴카테고리판매자"));
		deletedMember.changeStatus(MemberStatus.DELETED);
		Member suspendedMember = memberRepository.save(Member.createUser("category-visible-suspended@example.com", "encodedPassword", "정지카테고리판매자"));
		suspendedMember.changeStatus(MemberStatus.SUSPENDED);
		Category category = categoryRepository.save(new Category("공개카테고리정책"));
		Product visibleProduct = productRepository.save(Product.create(
				activeMember,
				category,
				"카테고리 공개 상품",
				"카테고리 공개 상품 설명",
				BigDecimal.valueOf(10000),
				saveGangnamWithDong()
		));
		Product completedProduct = Product.create(activeMember, category, "카테고리 거래완료 상품", "카테고리 거래완료 상품 설명", BigDecimal.valueOf(20000), saveGangnamWithDong());
		completedProduct.complete();
		productRepository.save(completedProduct);
		Product deletedSellerProduct = productRepository.save(Product.create(
				deletedMember,
				category,
				"카테고리 탈퇴 판매자 상품",
				"카테고리 탈퇴 판매자 상품 설명",
				BigDecimal.valueOf(30000),
				saveGangnamWithDong()
		));
		Product suspendedSellerProduct = productRepository.saveAndFlush(Product.create(
				suspendedMember,
				category,
				"카테고리 정지 판매자 상품",
				"카테고리 정지 판매자 상품 설명",
				BigDecimal.valueOf(40000),
				saveGangnamWithDong()
		));

		List<Product> products = productRepository.findAll(
				ProductSpecification.categoryList(category.getId()),
				Sort.by(Sort.Direction.DESC, "id")
		);

		assertThat(products).containsExactly(visibleProduct);
		assertThat(products).doesNotContain(completedProduct, deletedSellerProduct, suspendedSellerProduct);
	}

	private Region saveSeoul() {
		return regionRepository.findByCode("1100000000")
				.orElseGet(() -> regionRepository.save(Region.root("1100000000", "서울특별시", "서울특별시")));
	}

	private Region saveGangnam() {
		return regionRepository.findByCode("1168000000")
				.orElseGet(() -> regionRepository.save(Region.child("1168000000", 2, saveSeoul(), "서울특별시 강남구", "강남구")));
	}

	private Region saveGangnamWithDong() {
		return saveDong(saveGangnam(), "1168010100", "서울특별시 강남구 역삼동", "역삼동");
	}

	private Region saveSeochoWithDong() {
		Region seocho = regionRepository.findByCode("1165000000")
				.orElseGet(() -> regionRepository.save(Region.child("1165000000", 2, saveSeoul(), "서울특별시 서초구", "서초구")));
		return saveDong(seocho, "1165010800", "서울특별시 서초구 서초동", "서초동");
	}

	private Region saveMapoWithDong() {
		Region mapo = regionRepository.findByCode("1144000000")
				.orElseGet(() -> regionRepository.save(Region.child("1144000000", 2, saveSeoul(), "서울특별시 마포구", "마포구")));
		return saveDong(mapo, "1144012400", "서울특별시 마포구 연남동", "연남동");
	}

	private Region saveSongpaWithDong() {
		Region songpa = regionRepository.findByCode("1171000000")
				.orElseGet(() -> regionRepository.save(Region.child("1171000000", 2, saveSeoul(), "서울특별시 송파구", "송파구")));
		return saveDong(songpa, "1171010100", "서울특별시 송파구 잠실동", "잠실동");
	}

	private Region saveYongsanWithDong() {
		Region yongsan = regionRepository.findByCode("1117000000")
				.orElseGet(() -> regionRepository.save(Region.child("1117000000", 2, saveSeoul(), "서울특별시 용산구", "용산구")));
		return saveDong(yongsan, "1117013000", "서울특별시 용산구 이태원동", "이태원동");
	}

	private Region saveDong(Region parent, String code, String fullName, String displayName) {
		return regionRepository.findByCode(code)
				.orElseGet(() -> regionRepository.save(Region.child(code, 3, parent, fullName, displayName)));
	}
	}
