package com.dongnemarket.product.service

import com.dongnemarket.category.repository.CategoryRepository
import com.dongnemarket.global.common.event.ProductCompletedEvent
import com.dongnemarket.global.common.event.ProductPriceChangedEvent
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.manner.entity.MannerScore
import com.dongnemarket.manner.service.MannerScoreService
import com.dongnemarket.member.entity.MemberStatus
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.dto.ProductCreateRequest
import com.dongnemarket.product.dto.ProductPageResponse
import com.dongnemarket.product.dto.ProductResponse
import com.dongnemarket.product.dto.ProductSearchRequest
import com.dongnemarket.product.dto.ProductStatusUpdateRequest
import com.dongnemarket.product.dto.ProductSummaryResponse
import com.dongnemarket.product.dto.ProductUpdateRequest
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.ProductImage
import com.dongnemarket.product.entity.TradeStatus
import com.dongnemarket.product.repository.ProductImageRepository
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.product.repository.spec.ProductSpecification
import com.dongnemarket.region.entity.Region
import com.dongnemarket.region.repository.RegionRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.StringUtils
import java.math.BigDecimal

@Service
@Transactional(readOnly = true)
class ProductService(
    private val productRepository: ProductRepository,
    private val productImageRepository: ProductImageRepository,
    private val memberRepository: MemberRepository,
    private val categoryRepository: CategoryRepository,
    private val regionRepository: RegionRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val mannerScoreService: MannerScoreService,
) {
    @Transactional
    fun createProduct(
        memberId: Long,
        request: ProductCreateRequest,
    ): ProductResponse {
        validateRequest(request)
        val member =
            memberRepository
                .findById(memberId)
                .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
        val category =
            categoryRepository
                .findById(request.categoryId ?: throw BusinessException(ErrorCode.CATEGORY_NOT_FOUND))
                .orElseThrow { BusinessException(ErrorCode.CATEGORY_NOT_FOUND) }
        val region = getRequiredDongRegion(request.regionCode)

        val product =
            Product.create(
                member,
                category,
                request.title!!,
                request.description!!,
                request.price!!,
                region,
            )
        val savedProduct = productRepository.save(product)
        saveProductImages(savedProduct, request.imageUrls!!, request.thumbnailIndex)
        return ProductResponse.from(savedProduct, request.imageUrls)
    }

    /**
     * 목록 조회. 원본 Java 는 무인자·1인자 오버로드를 따로 뒀다.
     * 기본 인자 + `@JvmOverloads` 로 옮겨 Java 에서 보이는 시그니처 3개를 유지한다.
     */
    @JvmOverloads
    fun getProducts(
        regionCodes: List<String>? = null,
        cursor: Long? = null,
        size: Int = DEFAULT_PAGE_SIZE,
    ): ProductPageResponse {
        val normalizedRegionCodes = normalizeRegionCodes(regionCodes)
        validateRegionCodeFilterSize(normalizedRegionCodes)
        val limit = clampPageSize(size)

        val rows =
            productRepository.findBy<Product, List<Product>>(
                ProductSpecification.list(normalizedRegionCodes, cursor),
            ) { query ->
                query
                    .sortBy(Sort.by(Sort.Direction.DESC, "id"))
                    .limit(limit + 1)
                    .all()
            }
        val hasNext = rows.size > limit
        val page = if (hasNext) rows.subList(0, limit) else rows
        val nextCursor = if (hasNext) page[page.size - 1].id else null
        // 커서(다음 페이지 기준)는 항상 id 내림차순 조회 결과 그대로 계산한다 — 신뢰도 하락 정렬은
        // 여기서 확정된 페이지 내부의 노출 순서만 바꿀 뿐, 페이지네이션 자체에는 영향을 주지 않는다.
        val items = demoteLowTrustSellers(page).map { ProductSummaryResponse.from(it) }
        return ProductPageResponse.of(items, nextCursor, hasNext)
    }

    fun getProductsByCategory(categoryId: Long): List<ProductSummaryResponse> {
        if (!categoryRepository.existsById(categoryId)) {
            throw BusinessException(ErrorCode.CATEGORY_NOT_FOUND)
        }

        val products =
            productRepository.findAll(
                ProductSpecification.categoryList(categoryId),
                Sort.by(Sort.Direction.DESC, "id"),
            )
        return demoteLowTrustSellers(products).map { ProductSummaryResponse.from(it) }
    }

    fun getMyProducts(memberId: Long?): List<ProductSummaryResponse> {
        validateAuthenticatedMember(memberId)
        return productRepository
            .findAllByMemberIdAndDeletedAtIsNullOrderByIdDesc(memberId!!)
            .map { ProductSummaryResponse.from(it) }
    }

    fun searchProducts(request: ProductSearchRequest?): List<ProductSummaryResponse> {
        val searchRequest = normalizeSearchRequest(request)
        val regionCodes = normalizeRegionCodes(searchRequest.regionCodes)
        validateRegionCodeFilterSize(regionCodes)
        validateSearchPrice(searchRequest.minPrice, searchRequest.maxPrice)
        val tradeStatus = parseSearchTradeStatus(searchRequest.tradeStatus)

        val products =
            productRepository.findAll(
                ProductSpecification.search(
                    searchRequest.keyword,
                    searchRequest.categoryId,
                    searchRequest.minPrice,
                    searchRequest.maxPrice,
                    tradeStatus,
                    regionCodes,
                ),
                Sort.by(Sort.Direction.DESC, "id"),
            )
        return demoteLowTrustSellers(products).map { ProductSummaryResponse.from(it) }
    }

    /**
     * 판매자 매너온도가 저신뢰 임계치([MannerScore.LOW_TRUST_THRESHOLD]) 이하인 상품을
     * 목록 뒤쪽으로 밀어낸다(신고 목록 신뢰도 가중 정렬과 대칭되는 "노출 우선순위 하락").
     * 안정 정렬(stable sort)이라 각 그룹 내부의 원래 id 내림차순은 그대로 유지된다.
     */
    private fun demoteLowTrustSellers(products: List<Product>): List<Product> {
        if (products.isEmpty()) {
            return products
        }
        val sellerIds = products.mapTo(HashSet()) { it.member.id!! }
        val trustScores = mannerScoreService.getScoresByMemberIds(sellerIds)
        return products.sortedBy { isLowTrustSeller(it, trustScores) }
    }

    private fun isLowTrustSeller(
        product: Product,
        trustScores: Map<Long, BigDecimal>,
    ): Boolean {
        val score = trustScores.getOrDefault(product.member.id, MannerScore.DEFAULT_SCORE)
        return score <= MannerScore.LOW_TRUST_THRESHOLD
    }

    @Transactional
    fun getProduct(productId: Long): ProductResponse {
        val product =
            productRepository
                .findById(productId)
                .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }
        if (product.isDeleted) {
            throw BusinessException(ErrorCode.DELETED_PRODUCT)
        }
        if (product.isHidden) {
            throw BusinessException(ErrorCode.HIDDEN_PRODUCT)
        }
        if (product.member.status != MemberStatus.ACTIVE) {
            throw BusinessException(ErrorCode.PRODUCT_NOT_FOUND)
        }
        if (product.isCompleted) {
            throw BusinessException(ErrorCode.PRODUCT_NOT_FOUND)
        }

        product.increaseViewCount()
        val imageUrls = getImageUrls(productId)
        return ProductResponse.from(product, imageUrls)
    }

    @Transactional
    fun updateProduct(
        memberId: Long,
        productId: Long,
        request: ProductUpdateRequest,
    ): ProductResponse {
        validateRequest(request)
        val product =
            productRepository
                .findById(productId)
                .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }
        if (product.isDeleted) {
            throw BusinessException(ErrorCode.DELETED_PRODUCT)
        }
        if (product.member.id != memberId) {
            throw BusinessException(ErrorCode.PRODUCT_OWNER_ONLY)
        }
        if (product.isCompleted) {
            throw BusinessException(ErrorCode.CANNOT_UPDATE_COMPLETED_PRODUCT)
        }

        val category =
            categoryRepository
                .findById(request.categoryId ?: throw BusinessException(ErrorCode.CATEGORY_NOT_FOUND))
                .orElseThrow { BusinessException(ErrorCode.CATEGORY_NOT_FOUND) }
        val region = getRequiredDongRegion(request.regionCode)
        val oldPrice = product.price // update() 로 덮이기 전에 캡처
        product.update(
            category,
            request.title!!,
            request.description!!,
            request.price!!,
            region,
        )
        productImageRepository.deleteAllByProductId(productId)
        val managedProduct =
            productRepository
                .findById(productId)
                .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }
        saveProductImages(managedProduct, request.imageUrls!!, request.thumbnailIndex)

        // 가격이 실제로 바뀐 경우에만 알림 이벤트 발행. BigDecimal 은 scale 민감이라 compareTo 로 비교한다(equals X).
        // 커밋 후(AFTER_COMMIT) 별도 트랜잭션에서 처리되어 알림 실패가 상품 수정을 롤백하지 않는다(best-effort).
        if (oldPrice.compareTo(request.price) != 0) {
            eventPublisher.publishEvent(
                ProductPriceChangedEvent(productId, managedProduct.title, oldPrice, request.price),
            )
        }
        return ProductResponse.from(managedProduct, request.imageUrls)
    }

    @Transactional
    fun deleteProduct(
        memberId: Long,
        productId: Long,
    ) {
        val product =
            productRepository
                .findById(productId)
                .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }
        if (product.isDeleted) {
            throw BusinessException(ErrorCode.DELETED_PRODUCT)
        }
        if (product.member.id != memberId) {
            throw BusinessException(ErrorCode.PRODUCT_OWNER_ONLY)
        }

        product.softDelete()
    }

    @Transactional
    fun updateProductStatus(
        memberId: Long,
        productId: Long,
        request: ProductStatusUpdateRequest?,
    ): ProductResponse {
        val requestedStatus = parseTradeStatus(request)
        val product =
            productRepository
                .findById(productId)
                .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }
        if (product.isDeleted) {
            throw BusinessException(ErrorCode.DELETED_PRODUCT)
        }
        if (product.member.id != memberId) {
            throw BusinessException(ErrorCode.PRODUCT_OWNER_ONLY)
        }
        if (product.isCompleted && requestedStatus != TradeStatus.COMPLETED) {
            throw BusinessException(ErrorCode.CANNOT_CHANGE_COMPLETED_PRODUCT)
        }

        if (product.tradeStatus != requestedStatus) {
            product.changeTradeStatus(requestedStatus)
            // 매너온도 반영은 커밋 후 별도 트랜잭션에서 처리(best-effort), 상품 상태 변경 자체를 막지 않는다.
            if (requestedStatus == TradeStatus.COMPLETED) {
                eventPublisher.publishEvent(ProductCompletedEvent(productId, product.member.id!!))
            }
        }
        val imageUrls = getImageUrls(productId)
        return ProductResponse.from(product, imageUrls)
    }

    fun validateAccessibleProduct(productId: Long) {
        if (!productRepository.existsByIdAndDeletedAtIsNullAndIsHiddenFalse(productId)) {
            throw BusinessException(ErrorCode.PRODUCT_NOT_FOUND)
        }
    }

    private fun validateAuthenticatedMember(memberId: Long?) {
        if (memberId == null) {
            throw BusinessException(ErrorCode.UNAUTHORIZED)
        }
    }

    private fun validateRequest(request: ProductCreateRequest) {
        validateProductFields(request.title, request.price)
        validateProductImages(request.imageUrls, request.thumbnailIndex)
    }

    private fun validateRequest(request: ProductUpdateRequest) {
        validateProductFields(request.title, request.price)
        validateProductImages(request.imageUrls, request.thumbnailIndex)
    }

    private fun validateProductFields(
        title: String?,
        price: BigDecimal?,
    ) {
        if (!StringUtils.hasText(title)) {
            throw BusinessException(ErrorCode.INVALID_PRODUCT_TITLE)
        }
        if (price == null || price.signum() < 0) {
            throw BusinessException(ErrorCode.INVALID_PRODUCT_PRICE)
        }
    }

    private fun getRequiredDongRegion(regionCode: String?): Region {
        if (!StringUtils.hasText(regionCode)) {
            throw BusinessException(ErrorCode.INVALID_INPUT_VALUE)
        }
        val region =
            regionRepository
                .findByCode(regionCode!!)
                .orElseThrow { BusinessException(ErrorCode.INVALID_INPUT_VALUE) }
        if (region.level != 3) {
            throw BusinessException(ErrorCode.INVALID_INPUT_VALUE)
        }
        return region
    }

    private fun validateProductImages(
        imageUrls: List<String>?,
        thumbnailIndex: Int,
    ) {
        if (imageUrls.isNullOrEmpty() || imageUrls.size > MAX_IMAGE_COUNT) {
            throw BusinessException(ErrorCode.INVALID_INPUT_VALUE)
        }
        if (imageUrls.any { !StringUtils.hasText(it) }) {
            throw BusinessException(ErrorCode.INVALID_INPUT_VALUE)
        }
        if (thumbnailIndex < 0 || thumbnailIndex >= imageUrls.size) {
            throw BusinessException(ErrorCode.INVALID_INPUT_VALUE)
        }
    }

    private fun saveProductImages(
        product: Product,
        imageUrls: List<String>,
        thumbnailIndex: Int,
    ) {
        product.changeThumbnailUrl(imageUrls[thumbnailIndex])
        val productImages =
            imageUrls.mapIndexed { index, imageUrl ->
                ProductImage.create(product, imageUrl, index, index == thumbnailIndex)
            }
        productImageRepository.saveAll(productImages)
    }

    private fun getImageUrls(productId: Long): List<String> =
        productImageRepository
            .findAllByProductIdOrderBySortOrderAsc(productId)
            .map { it.imageUrl }

    private fun parseTradeStatus(request: ProductStatusUpdateRequest?): TradeStatus {
        if (request == null || !StringUtils.hasText(request.tradeStatus)) {
            throw BusinessException(ErrorCode.INVALID_TRADE_STATUS)
        }
        return try {
            TradeStatus.valueOf(request.tradeStatus!!)
        } catch (e: IllegalArgumentException) {
            throw BusinessException(ErrorCode.INVALID_TRADE_STATUS)
        }
    }

    private fun normalizeSearchRequest(request: ProductSearchRequest?): ProductSearchRequest = request ?: ProductSearchRequest()

    private fun normalizeRegionCodes(regionCodes: List<String>?): List<String> =
        regionCodes?.filter { StringUtils.hasText(it) } ?: emptyList()

    private fun validateRegionCodeFilterSize(regionCodes: List<String>) {
        if (regionCodes.size > MAX_REGION_FILTER_SIZE) {
            throw BusinessException(ErrorCode.INVALID_INPUT_VALUE)
        }
    }

    private fun clampPageSize(size: Int): Int {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE
        }
        return minOf(size, MAX_PAGE_SIZE)
    }

    private fun validateSearchPrice(
        minPrice: BigDecimal?,
        maxPrice: BigDecimal?,
    ) {
        if ((minPrice != null && minPrice.signum() < 0) || (maxPrice != null && maxPrice.signum() < 0)) {
            throw BusinessException(ErrorCode.INVALID_SEARCH_CONDITION)
        }
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            throw BusinessException(ErrorCode.INVALID_SEARCH_CONDITION)
        }
    }

    private fun parseSearchTradeStatus(tradeStatus: String?): TradeStatus? {
        if (tradeStatus == null) {
            return null
        }
        if (!StringUtils.hasText(tradeStatus)) {
            throw BusinessException(ErrorCode.INVALID_TRADE_STATUS)
        }
        return try {
            TradeStatus.valueOf(tradeStatus)
        } catch (e: IllegalArgumentException) {
            throw BusinessException(ErrorCode.INVALID_TRADE_STATUS)
        }
    }

    companion object {
        private const val MAX_REGION_FILTER_SIZE = 2
        private const val DEFAULT_PAGE_SIZE = 30
        private const val MAX_PAGE_SIZE = 100
        private const val MAX_IMAGE_COUNT = 5
    }
}
