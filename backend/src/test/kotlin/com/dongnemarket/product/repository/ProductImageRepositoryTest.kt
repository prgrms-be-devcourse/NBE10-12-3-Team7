package com.dongnemarket.product.repository

import com.dongnemarket.category.entity.Category
import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.global.config.JpaAuditingConfig
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.ProductImage
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import kotlin.math.abs

/**
 * [통합] 상품 이미지 리포지토리 — 정렬 순서 조회와 상품 단위 일괄 삭제.
 * 삭제는 대상 상품의 이미지만 지워야 하므로 다른 상품 이미지가 남아 있는지 함께 확인한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig::class)
class ProductImageRepositoryTest {
    @Autowired
    lateinit var productImageRepository: ProductImageRepository

    @Autowired
    lateinit var productRepository: ProductRepository

    @Autowired
    lateinit var memberRepository: MemberRepository

    @Autowired
    lateinit var categoryRepository: CategoryRepository

    @Autowired
    lateinit var regionRepository: RegionRepository

    @Test
    fun `상품 이미지는 정렬 순서 오름차순으로 조회한다`() {
        val product = saveProduct("image-order@example.com")
        val secondImage = productImageRepository.save(ProductImage.create(product, "https://example.com/2.jpg", 1, false))
        val firstImage = productImageRepository.save(ProductImage.create(product, "https://example.com/1.jpg", 0, true))
        val thirdImage = productImageRepository.save(ProductImage.create(product, "https://example.com/3.jpg", 2, false))

        val images = productImageRepository.findAllByProductIdOrderBySortOrderAsc(product.id!!)

        assertThat(images).containsExactly(firstImage, secondImage, thirdImage)
    }

    @Test
    fun `상품 ID 기준으로 기존 이미지를 모두 삭제한다`() {
        val targetProduct = saveProduct("image-delete-target@example.com")
        val otherProduct = saveProduct("image-delete-other@example.com")
        productImageRepository.save(ProductImage.create(targetProduct, "https://example.com/target-1.jpg", 0, true))
        productImageRepository.save(ProductImage.create(targetProduct, "https://example.com/target-2.jpg", 1, false))
        productImageRepository.save(ProductImage.create(otherProduct, "https://example.com/other-1.jpg", 0, true))

        productImageRepository.deleteAllByProductId(targetProduct.id!!)

        assertThat(productImageRepository.findAllByProductIdOrderBySortOrderAsc(targetProduct.id!!)).isEmpty()
        val otherImages = productImageRepository.findAllByProductIdOrderBySortOrderAsc(otherProduct.id!!)
        assertThat(otherImages).hasSize(1)
        assertThat(otherImages[0].imageUrl).isEqualTo("https://example.com/other-1.jpg")
        assertThat(otherImages[0].isRepresentative).isTrue()
    }

    private fun saveProduct(email: String): Product {
        val member = memberRepository.save(Member.createUser(email, "encodedPassword", "판매자-" + abs(email.hashCode())))
        val category = categoryRepository.save(Category("이미지-" + abs(email.hashCode())))
        return productRepository.saveAndFlush(
            Product.create(
                member,
                category,
                "이미지 테스트 상품",
                "이미지 테스트 상품 설명입니다.",
                BigDecimal.valueOf(10000),
                findRegion("1168010100"),
            ),
        )
    }

    private fun findRegion(code: String): Region {
        val seoul =
            regionRepository
                .findByCode("1100000000")
                .orElseGet { regionRepository.save(Region.root("1100000000", "서울특별시", "서울특별시")) }
        val gangnam =
            regionRepository
                .findByCode("1168000000")
                .orElseGet { regionRepository.save(Region.child("1168000000", 2, seoul, "서울특별시 강남구", "강남구")) }
        return regionRepository
            .findByCode(code)
            .orElseGet { regionRepository.save(Region.child(code, 3, gangnam, "서울특별시 강남구 역삼동", "역삼동")) }
    }
}
