package com.dongnemarket.mobile.data.mapper

import com.dongnemarket.mobile.BuildConfig
import com.dongnemarket.mobile.data.remote.dto.ProductPageResponse
import com.dongnemarket.mobile.data.remote.dto.ProductResponse
import com.dongnemarket.mobile.data.remote.dto.ProductSummaryResponse
import com.dongnemarket.mobile.domain.model.TradeStatus
import com.dongnemarket.mobile.domain.model.RegionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * 상품 DTO → 도메인 매핑 명세.
 *
 * 이 경계에서 서버 사실 3가지가 앱 사실로 번역된다.
 *  1. `tradeStatus` 는 **문자열**로 온다 → 모르는 값이 와도 앱이 죽으면 안 된다.
 *  2. 이미지 경로는 **상대 경로**로 온다 → 절대 URL 로 바꿔야 Coil 이 로드한다.
 *  3. `price` 는 **스케일이 들쭉날쭉한 BigDecimal** 이다(`800000.00` vs `800000`).
 */
class ProductMapperTest {

    // ────────────────────────── tradeStatus 변환 ──────────────────────────

    @Test
    fun `서버가 준 ON_SALE 은 판매중 상태로 변환된다`() {
        // Given — 홈 목록에 실제로 실려 오는 판매중 상품 1건
        val response = summaryResponse(tradeStatus = "ON_SALE")

        // When
        val product = response.toDomain()

        // Then
        assertEquals(TradeStatus.ON_SALE, product.tradeStatus)
    }

    @Test
    fun `서버가 준 RESERVED 는 예약중 상태로 변환된다`() {
        // Given
        val response = summaryResponse(tradeStatus = "RESERVED")

        // When
        val product = response.toDomain()

        // Then
        assertEquals(TradeStatus.RESERVED, product.tradeStatus)
    }

    @Test
    fun `서버가 준 COMPLETED 는 거래완료 상태로 변환된다`() {
        // Given — 목록에는 안 오지만 채팅방의 상품 정보에서 관측된다
        val response = summaryResponse(tradeStatus = "COMPLETED")

        // When
        val product = response.toDomain()

        // Then
        assertEquals(TradeStatus.COMPLETED, product.tradeStatus)
    }

    @Test
    fun `앱이 모르는 상태값 AUCTION 이 와도 예외 없이 UNKNOWN 으로 떨어진다`() {
        // Given — 백엔드에 상태가 하나 추가된 상황을 가정한다.
        //         valueOf() 를 썼다면 여기서 IllegalArgumentException 이 나고 목록 화면 전체가 죽는다.
        val response = summaryResponse(tradeStatus = "AUCTION")

        // When
        val product = response.toDomain()

        // Then — 그 카드의 배지만 사라질 뿐 앱은 계속 돌아야 한다
        assertEquals(TradeStatus.UNKNOWN, product.tradeStatus)
    }

    @Test
    fun `tradeStatus 가 null 로 와도 크래시하지 않고 UNKNOWN 이 된다`() {
        // Given
        val response = summaryResponse(tradeStatus = null)

        // When
        val product = response.toDomain()

        // Then
        assertEquals(TradeStatus.UNKNOWN, product.tradeStatus)
    }

    @Test
    fun `대소문자가 다른 on_sale 은 판매중으로 인정하지 않고 UNKNOWN 으로 둔다`() {
        // Given — 서버는 항상 대문자로 준다. 소문자는 계약 위반이므로 추측해서 살려 주지 않는다.
        val response = summaryResponse(tradeStatus = "on_sale")

        // When
        val product = response.toDomain()

        // Then
        assertEquals(TradeStatus.UNKNOWN, product.tradeStatus)
    }

    // ────────────────────────── 이미지 URL 변환 ──────────────────────────

    @Test
    fun `상대경로 썸네일에는 BASE_URL 이 붙어 절대 URL 이 된다`() {
        // Given — 서버는 presigned URL 이 아니라 "/api/products/images/{uuid}.jpg" 만 준다
        val response = summaryResponse(thumbnailUrl = "/api/products/images/uuid-1.jpg")

        // When
        val product = response.toDomain()

        // Then — 슬래시가 두 번 붙지 않은 절대 URL 이어야 Coil 이 로드한다
        assertEquals(
            BuildConfig.BASE_URL.trimEnd('/') + "/api/products/images/uuid-1.jpg",
            product.thumbnailUrl,
        )
    }

    @Test
    fun `이미 https 로 시작하는 썸네일에는 BASE_URL 을 덧붙이지 않는다`() {
        // Given — 서버가 이미지 URL 문자열을 검증 없이 저장해 절대 URL 이 섞여 있다(계약 §0.8)
        val response = summaryResponse(thumbnailUrl = "https://example.com/old-2.jpg")

        // When
        val product = response.toDomain()

        // Then — 이중 접두("http://10.0.2.2:8080/https://...")가 생기면 안 된다
        assertEquals("https://example.com/old-2.jpg", product.thumbnailUrl)
    }

    @Test
    fun `썸네일이 null 이면 매퍼가 더미 URL 을 채우지 않고 null 로 남긴다`() {
        // Given — 시드·데모 데이터에는 이미지가 아예 없다
        val response = summaryResponse(thumbnailUrl = null)

        // When
        val product = response.toDomain()

        // Then — 여기서 가짜 URL 을 채우면 화면이 '이미지 있음'으로 착각해 깨진 이미지를 그린다.
        //        플레이스홀더는 UI 의 책임이다.
        assertNull(product.thumbnailUrl)
    }

    @Test
    fun `썸네일이 빈 문자열이어도 null 로 정규화된다`() {
        // Given
        val response = summaryResponse(thumbnailUrl = "   ")

        // When
        val product = response.toDomain()

        // Then
        assertNull(product.thumbnailUrl)
    }

    @Test
    fun `baseUrl 끝 슬래시와 상대경로 앞 슬래시가 겹쳐도 슬래시는 하나만 남는다`() {
        // Given
        val relativePath = "/api/products/images/uuid-1.jpg"

        // When
        val absolute = relativePath.toAbsoluteImageUrl(baseUrl = "https://marketon.inyeon.io/")

        // Then
        assertEquals("https://marketon.inyeon.io/api/products/images/uuid-1.jpg", absolute)
    }

    @Test
    fun `슬래시로 시작하지 않는 상대경로에는 슬래시가 하나 끼워진다`() {
        // Given
        val relativePath = "api/products/images/uuid-1.jpg"

        // When
        val absolute = relativePath.toAbsoluteImageUrl(baseUrl = "https://marketon.inyeon.io/")

        // Then
        assertEquals("https://marketon.inyeon.io/api/products/images/uuid-1.jpg", absolute)
    }

    @Test
    fun `http 로 시작하는 URL 은 baseUrl 이 무엇이든 손대지 않는다`() {
        // Given
        val absoluteFromServer = "http://cdn.example.com/a.jpg"

        // When
        val absolute = absoluteFromServer.toAbsoluteImageUrl(baseUrl = "https://marketon.inyeon.io/")

        // Then
        assertEquals("http://cdn.example.com/a.jpg", absolute)
    }

    @Test
    fun `상세의 이미지 목록은 상대경로만 절대 URL 로 바뀌고 절대 URL 은 그대로 통과한다`() {
        // Given — 한 상품에 상대경로와 절대 URL 이 섞여 저장돼 있을 수 있다
        val response = productResponse(
            imageUrls = listOf("/api/products/images/a.jpg", "https://example.com/old-2.jpg"),
        )

        // When
        val detail = response.toDomain()

        // Then
        assertEquals(
            listOf(
                BuildConfig.BASE_URL.trimEnd('/') + "/api/products/images/a.jpg",
                "https://example.com/old-2.jpg",
            ),
            detail.imageUrls,
        )
    }

    @Test
    fun `상세 이미지 목록의 빈 문자열 원소는 걸러진다`() {
        // Given — 그릴 수 없는 원소가 리스트에 남으면 뷰페이저에 빈 페이지가 생긴다
        val response = productResponse(imageUrls = listOf("/api/products/images/a.jpg", "", "  "))

        // When
        val detail = response.toDomain()

        // Then
        assertEquals(1, detail.imageUrls.size)
    }

    @Test
    fun `이미지가 없는 상품의 상세 이미지 목록은 빈 리스트다`() {
        // Given
        val response = productResponse(imageUrls = emptyList())

        // When
        val detail = response.toDomain()

        // Then
        assertTrue(detail.imageUrls.isEmpty())
    }

    // ────────────────────────── price(BigDecimal) 취급 ──────────────────────────

    @Test
    fun `800000점00 으로 온 가격과 800000 으로 온 가격은 compareTo 로 같은 금액이다`() {
        // Given — 같은 상품인데 GET 은 "800000.00", POST 응답 echo 는 "800000" 으로 준다(계약 §0.11)
        val fromGet = summaryResponse(price = BigDecimal("800000.00")).toDomain()
        val fromEcho = summaryResponse(price = BigDecimal("800000")).toDomain()

        // When
        val comparison = fromGet.price.compareTo(fromEcho.price)

        // Then — 금액 비교는 반드시 compareTo 로 한다
        assertEquals(0, comparison)
    }

    @Test
    fun `스케일이 다른 가격은 equals 로 비교하면 서로 다른 상품으로 판정된다`() {
        // Given — 위와 완전히 같은 두 상품
        val fromGet = summaryResponse(price = BigDecimal("800000.00")).toDomain()
        val fromEcho = summaryResponse(price = BigDecimal("800000")).toDomain()

        // When / Then — data class equals 가 BigDecimal.equals(스케일까지 비교)를 타기 때문에
        //               "같은 금액인데 다른 객체"가 된다. 목록 비교·중복 제거를 price 로 하면 안 되는 이유다.
        assertNotEquals(fromGet, fromEcho)
    }

    @Test
    fun `가격이 0원이고 판매중이면 나눔으로 판정한다`() {
        // Given — 서버에 '나눔' 상태가 없어서 0원을 나눔으로 읽는 것이 앱 측 규약이다
        val response = summaryResponse(price = BigDecimal("0.00"), tradeStatus = "ON_SALE")

        // When
        val product = response.toDomain()

        // Then
        assertTrue(product.isGiveaway)
    }

    @Test
    fun `가격이 0원이어도 예약중이면 나눔 배지를 달지 않는다`() {
        // Given — 예약중 배지가 나눔 배지에 덮이면 거래 상태를 알 수 없게 된다
        val response = summaryResponse(price = BigDecimal("0.00"), tradeStatus = "RESERVED")

        // When
        val product = response.toDomain()

        // Then
        assertFalse(product.isGiveaway)
    }

    @Test
    fun `가격이 1원이라도 있으면 나눔이 아니다`() {
        // Given
        val response = summaryResponse(price = BigDecimal("1.00"), tradeStatus = "ON_SALE")

        // When
        val product = response.toDomain()

        // Then
        assertFalse(product.isGiveaway)
    }

    // ────────────────────────── 필드 이름 번역 · null 방어 ──────────────────────────

    @Test
    fun `서버 키 memberId 는 도메인 sellerId 로 옮겨진다`() {
        // Given — 서버는 판매자 PK 를 memberId 라고 부른다
        val response = summaryResponse(memberId = 77L)

        // When
        val product = response.toDomain()

        // Then
        assertEquals(77L, product.sellerId)
    }

    @Test
    fun `지역 3필드가 null 로 와도 빈 문자열로 흡수돼 카드가 죽지 않는다`() {
        // Given
        val response = summaryResponse(regionCode = null, regionName = null, regionFullName = null)

        // When
        val product = response.toDomain()

        // Then
        assertEquals(RegionRef.EMPTY, product.region)
        assertEquals("", product.region.display)
    }

    @Test
    fun `상세의 sellerNickname 이 null 이면 빈 문자열이 된다`() {
        // Given — 탈퇴 회원이면 서버가 "탈퇴한 사용자" 를 넣어 주지만 null 방어도 필요하다
        val response = productResponse(sellerNickname = null)

        // When
        val detail = response.toDomain()

        // Then
        assertEquals("", detail.sellerNickname)
    }

    @Test
    fun `상세의 description 이 null 이면 빈 문자열이 된다`() {
        // Given
        val response = productResponse(description = null)

        // When
        val detail = response.toDomain()

        // Then
        assertEquals("", detail.description)
    }

    @Test
    fun `상세 조회로 올라간 viewCount 는 그대로 도메인에 실린다`() {
        // Given — 이 GET 이 서버에서 조회수를 +1 한 뒤의 값이다
        val response = productResponse(viewCount = 13L)

        // When
        val detail = response.toDomain()

        // Then
        assertEquals(13L, detail.viewCount)
    }

    // ────────────────────────── 커서 페이지 래퍼 ──────────────────────────

    @Test
    fun `페이지 응답의 items 는 서버가 준 순서 그대로 도메인 상품 목록이 된다`() {
        // Given — 목록 정렬은 id DESC 고정이므로 앱이 재정렬하면 안 된다
        val response = ProductPageResponse(
            items = listOf(
                summaryResponse(productId = 103L, title = "자전거"),
                summaryResponse(productId = 101L, title = "아이폰 15 프로"),
            ),
            nextCursor = 101L,
            hasNext = true,
        )

        // When
        val page = response.toDomain()

        // Then
        assertEquals(listOf(103L, 101L), page.items.map { it.productId })
    }

    @Test
    fun `다음 페이지가 있으면 nextCursor 가 그대로 전달된다`() {
        // Given
        val response = ProductPageResponse(
            items = listOf(summaryResponse(productId = 101L)),
            nextCursor = 101L,
            hasNext = true,
        )

        // When
        val page = response.toDomain()

        // Then — 다음 요청에 이 값을 그대로 넘긴다
        assertEquals(101L, page.nextCursor)
    }

    @Test
    fun `마지막 페이지는 nextCursor 가 null 이어도 hasNext 값으로 끝을 판단한다`() {
        // Given — 서버는 마지막 페이지에서도 nextCursor 키를 남긴 채 null 로 준다
        val response = ProductPageResponse(items = emptyList(), nextCursor = null, hasNext = false)

        // When
        val page = response.toDomain()

        // Then
        assertFalse(page.hasNext)
    }

    // ────────────────────────── 테스트용 응답 조립 ──────────────────────────

    /** `GET /api/products` 응답 items 1건. 계약 §5.4 필드 구성 그대로. */
    private fun summaryResponse(
        productId: Long = 101L,
        memberId: Long = 7L,
        categoryId: Long = 1L,
        title: String = "아이폰 15 프로",
        price: BigDecimal = BigDecimal("800000.00"),
        tradeStatus: String? = "ON_SALE",
        regionCode: String? = "11680",
        regionName: String? = "강남구",
        regionFullName: String? = "서울특별시 강남구",
        viewCount: Long = 12L,
        favoriteCount: Int = 3,
        thumbnailUrl: String? = null,
        hidden: Boolean = false,
    ) = ProductSummaryResponse(
        productId = productId,
        memberId = memberId,
        categoryId = categoryId,
        title = title,
        price = price,
        tradeStatus = tradeStatus,
        regionCode = regionCode,
        regionName = regionName,
        regionFullName = regionFullName,
        viewCount = viewCount,
        favoriteCount = favoriteCount,
        thumbnailUrl = thumbnailUrl,
        hidden = hidden,
    )

    /** `GET /api/products/{productId}` 응답. 계약 §5.4 필드 구성 그대로. */
    private fun productResponse(
        productId: Long = 101L,
        memberId: Long = 7L,
        sellerNickname: String? = "동네주민",
        categoryId: Long = 1L,
        title: String = "아이폰 15 프로",
        description: String? = "생활기스 있습니다.",
        price: BigDecimal = BigDecimal("800000.00"),
        tradeStatus: String? = "ON_SALE",
        regionCode: String? = "11680",
        regionName: String? = "강남구",
        regionFullName: String? = "서울특별시 강남구",
        viewCount: Long = 13L,
        favoriteCount: Int = 3,
        thumbnailUrl: String? = null,
        imageUrls: List<String> = emptyList(),
        hidden: Boolean = false,
    ) = ProductResponse(
        productId = productId,
        memberId = memberId,
        sellerNickname = sellerNickname,
        categoryId = categoryId,
        title = title,
        description = description,
        price = price,
        tradeStatus = tradeStatus,
        regionCode = regionCode,
        regionName = regionName,
        regionFullName = regionFullName,
        viewCount = viewCount,
        favoriteCount = favoriteCount,
        thumbnailUrl = thumbnailUrl,
        imageUrls = imageUrls,
        hidden = hidden,
    )
}
