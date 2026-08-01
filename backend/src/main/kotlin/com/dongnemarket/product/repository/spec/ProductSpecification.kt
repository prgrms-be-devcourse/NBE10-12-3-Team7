package com.dongnemarket.product.repository.spec

import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.TradeStatus
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import org.springframework.util.StringUtils
import java.math.BigDecimal

/**
 * 상품 목록·검색 조건 조립기. Java 원본의 `private` 생성자 + `static` 유틸 구조를 `object` 로 옮긴다.
 *
 * 공개 팩토리에 `@JvmStatic` 이 필요한 이유: 아직 Java 인 `ProductService`·`ProductRepositoryTest`
 * 가 `ProductSpecification.list(...)` 로 호출한다. 없으면 `ProductSpecification.INSTANCE.list(...)`
 * 가 되어 깨진다.
 *
 * 각 조건이 `null` 을 반환하는 것은 "이 조건은 없는 셈 치라"는 뜻이다.
 * `Specification.toPredicate` 는 `@Nullable` 이고, `and()` 체인이 null 을 걸러낸다.
 * 그래서 조건을 무조건 이어붙여도 실제 지정된 것만 WHERE 절에 들어간다.
 *
 * `root.get<T>(...)` 의 타입 인자를 명시하는 이유: Java 는 `Path<Y> get(String)` 의 `Y` 를
 * 문맥에서 추론하지만 Kotlin 은 추론하지 못한다.
 */
object ProductSpecification {
    /**
     * 기본 목록 조건. `cursor` 가 있으면 그 id 미만만 조회한다(커서 페이징).
     *
     * 원본 Java 는 무인자 오버로드를 따로 뒀다. 기본 인자 + `@JvmOverloads` 로 옮겨
     * Java 에서 보이는 시그니처 2개를 그대로 유지한다.
     */
    @JvmStatic
    @JvmOverloads
    fun list(
        regionCodes: List<String>?,
        cursor: Long? = null,
    ): Specification<Product> =
        visibleProducts()
            .and(regionCodeStartsWithAny(regionCodes))
            .and(idLessThan(cursor))

    @JvmStatic
    fun search(
        keyword: String?,
        categoryId: Long?,
        minPrice: BigDecimal?,
        maxPrice: BigDecimal?,
        tradeStatus: TradeStatus?,
        regionCodes: List<String>?,
    ): Specification<Product> =
        visibleProducts()
            .and(keywordContains(keyword))
            .and(categoryEquals(categoryId))
            .and(priceGreaterThanOrEqualTo(minPrice))
            .and(priceLessThanOrEqualTo(maxPrice))
            .and(tradeStatusEquals(tradeStatus))
            .and(regionCodeStartsWithAny(regionCodes))

    @JvmStatic
    fun categoryList(categoryId: Long?): Specification<Product> =
        visibleProducts()
            .and(categoryEquals(categoryId))

    private fun visibleProducts(): Specification<Product> =
        notDeleted()
            .and(notHidden())
            .and(notCompleted())
            .and(activeSeller())

    private fun notDeleted(): Specification<Product> =
        Specification { root, _, criteriaBuilder ->
            criteriaBuilder.isNull(root.get<Any>("deletedAt"))
        }

    /** 프로퍼티명이 `hidden` 이 아니라 `isHidden` 이다(Product.kt 의 `@Column(name = "hidden")` 참고). */
    private fun notHidden(): Specification<Product> =
        Specification { root, _, criteriaBuilder ->
            criteriaBuilder.isFalse(root.get<Boolean>("isHidden"))
        }

    private fun notCompleted(): Specification<Product> =
        Specification { root, _, criteriaBuilder ->
            criteriaBuilder.notEqual(root.get<Any>("tradeStatus"), TradeStatus.COMPLETED)
        }

    private fun activeSeller(): Specification<Product> =
        Specification { root, _, criteriaBuilder ->
            criteriaBuilder.equal(root.get<Any>("member").get<Any>("status"), MemberStatus.ACTIVE)
        }

    private fun keywordContains(keyword: String?): Specification<Product> =
        Specification { root, _, criteriaBuilder ->
            if (!StringUtils.hasText(keyword)) {
                null
            } else {
                val keywordPattern = "%" + keyword!!.lowercase() + "%"
                criteriaBuilder.or(
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("title")), keywordPattern),
                    criteriaBuilder.like(criteriaBuilder.lower(root.get("description")), keywordPattern),
                )
            }
        }

    private fun categoryEquals(categoryId: Long?): Specification<Product> =
        Specification { root, _, criteriaBuilder ->
            if (categoryId == null) {
                null
            } else {
                criteriaBuilder.equal(root.get<Any>("category").get<Any>("id"), categoryId)
            }
        }

    private fun priceGreaterThanOrEqualTo(minPrice: BigDecimal?): Specification<Product> =
        Specification { root, _, criteriaBuilder ->
            if (minPrice == null) {
                null
            } else {
                criteriaBuilder.greaterThanOrEqualTo(root.get("price"), minPrice)
            }
        }

    private fun priceLessThanOrEqualTo(maxPrice: BigDecimal?): Specification<Product> =
        Specification { root, _, criteriaBuilder ->
            if (maxPrice == null) {
                null
            } else {
                criteriaBuilder.lessThanOrEqualTo(root.get("price"), maxPrice)
            }
        }

    private fun tradeStatusEquals(tradeStatus: TradeStatus?): Specification<Product> =
        Specification { root, _, criteriaBuilder ->
            if (tradeStatus == null) {
                null
            } else {
                criteriaBuilder.equal(root.get<Any>("tradeStatus"), tradeStatus)
            }
        }

    private fun regionCodeStartsWithAny(regionCodes: List<String>?): Specification<Product> =
        Specification { root, _, criteriaBuilder ->
            if (regionCodes.isNullOrEmpty()) {
                null
            } else {
                val region = root.join<Any, Any>("regionRef")
                val predicates: List<Predicate> =
                    regionCodes
                        .filter { StringUtils.hasText(it) }
                        .map { toRegionCodePrefix(it) }
                        .map { prefix -> criteriaBuilder.like(region.get("code"), "$prefix%") }
                if (predicates.isEmpty()) {
                    null
                } else {
                    criteriaBuilder.or(*predicates.toTypedArray())
                }
            }
        }

    private fun toRegionCodePrefix(regionCode: String): String {
        if (regionCode.length >= 10 && regionCode.endsWith("00000000")) {
            return regionCode.substring(0, 2)
        }
        if (regionCode.length >= 10 && regionCode.endsWith("00000")) {
            return regionCode.substring(0, 5)
        }
        return regionCode
    }

    private fun idLessThan(cursor: Long?): Specification<Product> =
        Specification { root, _, criteriaBuilder ->
            if (cursor == null) {
                null
            } else {
                criteriaBuilder.lessThan(root.get("id"), cursor)
            }
        }
}
