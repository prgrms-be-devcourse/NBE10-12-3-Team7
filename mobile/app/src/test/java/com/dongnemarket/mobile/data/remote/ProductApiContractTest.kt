package com.dongnemarket.mobile.data.remote

import com.dongnemarket.mobile.BuildConfig
import com.dongnemarket.mobile.data.image.ImageCompressor
import com.dongnemarket.mobile.data.local.TokenDataStore
import com.dongnemarket.mobile.data.repository.ProductRepositoryImpl
import com.dongnemarket.mobile.di.NetworkModule
import com.dongnemarket.mobile.domain.model.AppError
import com.dongnemarket.mobile.domain.model.NewProduct
import com.dongnemarket.mobile.domain.model.TradeStatus
import com.dongnemarket.mobile.domain.repository.ProductRepository
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import java.math.BigDecimal

/**
 * 상품 API **계약 테스트**. 목(mock)을 쓰지 않고 진짜 Retrofit + kotlinx.serialization 을
 * 가짜 서버([MockWebServer])에 붙여, 아래 두 방향을 실물로 검증한다.
 *
 *  - **나가는 요청**: 쿼리 파라미터가 서버가 읽을 수 있는 모양으로 직렬화되는가
 *    (`regions` 반복 파라미터 / `cursor` 생략)
 *  - **들어오는 응답**: 계약 문서 §2-3·§2-4·§3-1 의 **실제 JSON** 이
 *    도메인 모델까지 손실 없이 흘러가는가 (`price` BigDecimal, 페이징 래퍼, 이미지 URL, 에러 번역)
 *
 * Json 설정과 OkHttp 조립은 **제품 코드([NetworkModule])를 그대로 호출**한다.
 * 테스트가 자기만의 Json 을 새로 만들면 정작 앱이 쓰는 설정(`coerceInputValues` 등)은
 * 검증되지 않은 채로 남기 때문이다.
 */
class ProductApiContractTest {

    private lateinit var server: MockWebServer

    /** 구현체를 그대로 쓰되 타입은 인터페이스로 둔다(화면이 보는 계약과 같은 시선). */
    private lateinit var repository: ProductRepository

    /**
     * 압축기만 목이다. `Bitmap`·`ContentResolver` 는 Android 프레임워크라 JVM 에서 돌지 않는다.
     * 이 테스트의 관심사는 **압축 결과가 실제 HTTP 요청으로 어떻게 나가는가**이므로
     * "이 바이트가 나왔다"고 가정하고 그 뒤를 전부 실물로 검증한다.
     */
    private val imageCompressor = mockk<ImageCompressor>()

    /** 가짜 서버를 띄우고 실제 Retrofit 배관(제품 코드의 Json·OkHttp)을 연결한다. */
    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        // DataStore 는 Android 의존이라 JVM 에서 못 쓴다 → 토큰 흐름만 목으로 대체한다.
        val tokenDataStore = mockk<TokenDataStore>()
        every { tokenDataStore.accessToken } returns flowOf("test-access-token")

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(NetworkModule.provideOkHttpClient(AuthInterceptor(tokenDataStore)))
            .addConverterFactory(
                NetworkModule.provideJson().asConverterFactory("application/json".toMediaType()),
            )
            .build()

        repository = ProductRepositoryImpl(
            api = retrofit.create(ProductApiService::class.java),
            imageCompressor = imageCompressor,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ────────────────────────────────────────────────────────────────
    // 나가는 요청 — 쿼리 파라미터 직렬화
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `홈 목록의 regions 필터는 콤마 연결이 아니라 반복 파라미터로 나간다`() = runTest {
        // Given: 서버는 빈 페이지를 준다(이 테스트의 관심사는 '보낸 요청'뿐이다)
        server.enqueue(성공응답(빈_상품_페이지_JSON))

        // When: 내 동네 2개로 목록을 조회하면
        repository.getProducts(regionCodes = listOf("서울 강남구", "서울 마포구"))

        // Then: ?regions=서울 강남구&regions=서울 마포구 로 두 번 실린다
        //       (콤마 join 이나 배열 JSON 으로 보내면 서버가 읽지 못한다)
        val 요청 = server.takeRequest()
        assertEquals(
            listOf("서울 강남구", "서울 마포구"),
            요청.requestUrl!!.queryParameterValues("regionCodes"),
        )
    }

    @Test
    fun `첫 페이지 요청에는 cursor 파라미터가 아예 붙지 않는다`() = runTest {
        // Given
        server.enqueue(성공응답(빈_상품_페이지_JSON))

        // When: 커서 없이(=첫 페이지) 조회하면
        repository.getProducts(cursor = null)

        // Then: cursor 키 자체가 사라진다.
        //       빈 문자열이라도 실려 나가면 서버가 400 이 아니라 500 을 낸다(계약 §7-4).
        val 쿼리키 = server.takeRequest().requestUrl!!.queryParameterNames
        assertEquals(setOf("size"), 쿼리키)
    }

    @Test
    fun `다음 페이지 요청에는 받은 nextCursor 가 cursor 파라미터로 붙는다`() = runTest {
        // Given
        server.enqueue(성공응답(빈_상품_페이지_JSON))

        // When: 이전 페이지에서 받은 커서로 이어서 조회하면
        repository.getProducts(cursor = 128L)

        // Then
        assertEquals("128", server.takeRequest().requestUrl!!.queryParameter("cursor"))
    }

    @Test
    fun `지역을 3개 넘기면 앞의 2개만 서버로 보낸다`() = runTest {
        // Given: 서버는 regions 가 3개 이상이면 400 INVALID_INPUT_VALUE 를 낸다(계약 §7-19)
        server.enqueue(성공응답(빈_상품_페이지_JSON))

        // When
        repository.getProducts(regionCodes = listOf("서울 강남구", "서울 마포구", "서울 송파구"))

        // Then: 400 을 맞기 전에 클라이언트가 잘라 낸다
        assertEquals(
            listOf("서울 강남구", "서울 마포구"),
            server.takeRequest().requestUrl!!.queryParameterValues("regionCodes"),
        )
    }

    @Test
    fun `공백과 중복이 섞인 지역 목록은 정리되어 하나만 나간다`() = runTest {
        // Given
        server.enqueue(성공응답(빈_상품_페이지_JSON))

        // When
        repository.getProducts(regionCodes = listOf(" 서울 강남구 ", "서울 강남구", "   "))

        // Then: 트림 → 공백 제거 → 중복 제거
        assertEquals(
            listOf("서울 강남구"),
            server.takeRequest().requestUrl!!.queryParameterValues("regionCodes"),
        )
    }

    @Test
    fun `검색어가 공백뿐이면 keyword 파라미터를 빼고 보낸다`() = runTest {
        // Given: "제목에 ''를 포함" 이라는 무의미한 조건이 붙지 않아야 한다
        server.enqueue(성공응답(검색_결과_JSON))

        // When
        repository.searchProducts(keyword = "   ", categoryId = 1L)

        // Then
        val 쿼리키 = server.takeRequest().requestUrl!!.queryParameterNames
        assertEquals(setOf("categoryId"), 쿼리키)
    }

    @Test
    fun `상세 요청은 GET api-products-productId 경로로 나간다`() = runTest {
        // Given
        server.enqueue(성공응답(상품_상세_JSON))

        // When
        repository.getProductDetail(77L)

        // Then: 선행 슬래시로 baseUrl 이 잘리지 않고 그대로 이어붙는다
        val 요청 = server.takeRequest()
        assertEquals("GET /api/products/77", "${요청.method} ${요청.path}")
    }

    // ────────────────────────────────────────────────────────────────
    // 들어오는 응답 — price(BigDecimal)
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `price 가 800000_00 으로 온 상품과 800000 으로 온 상품은 같은 금액이다`() = runTest {
        // Given: GET 은 DB decimal(38,2) 를 읽어 800000.00, POST 응답은 요청 echo 라 800000 이다
        server.enqueue(성공응답(상품_페이지_JSON))          // price: 800000.00
        server.enqueue(성공응답(검색_결과_JSON))            // price: 800000 (첫 원소)

        // When
        val 소수부표기 = repository.getProducts().getOrThrow().items.first().price
        val 정수표기 = repository.searchProducts().getOrThrow().first().price

        // Then: 스케일이 달라도 '금액'으로는 같다 → 비교는 반드시 compareTo 로 한다
        assertEquals(0, 소수부표기.compareTo(정수표기))
    }

    @Test
    fun `소수부가 있는 price 는 반올림 없이 그대로 파싱된다`() = runTest {
        // Given: 상세 응답의 price 는 1234.56 이다
        server.enqueue(성공응답(상품_상세_JSON))

        // When
        val 가격 = repository.getProductDetail(77L).getOrThrow().price

        // Then: Int/Long 파싱이면 여기서 깨지고, Double 파싱이면 오차가 섞인다
        assertEquals(0, BigDecimal("1234.56").compareTo(가격))
    }

    // ────────────────────────────────────────────────────────────────
    // 들어오는 응답 — 페이징 래퍼 / 매핑
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `목록 응답의 memberId 는 도메인에서 sellerId 로 매핑된다`() = runTest {
        // Given
        server.enqueue(성공응답(상품_페이지_JSON))

        // When
        val 첫상품 = repository.getProducts().getOrThrow().items.first()

        // Then: 서버 어휘(memberId) → 앱 어휘(sellerId)
        assertEquals(7L, 첫상품.sellerId)
    }

    @Test
    fun `목록 응답의 items 개수가 도메인 페이지에 그대로 담긴다`() = runTest {
        // Given: items 2건짜리 페이지
        server.enqueue(성공응답(상품_페이지_JSON))

        // When
        val 페이지 = repository.getProducts().getOrThrow()

        // Then
        assertEquals(2, 페이지.items.size)
    }

    @Test
    fun `다음 페이지가 남아 있으면 hasNext 가 true 로 전달된다`() = runTest {
        // Given
        server.enqueue(성공응답(상품_페이지_JSON))

        // When
        val 페이지 = repository.getProducts().getOrThrow()

        // Then
        assertTrue(페이지.hasNext)
    }

    @Test
    fun `nextCursor 는 다음 요청에 쓸 커서 값으로 그대로 전달된다`() = runTest {
        // Given: 이 페이지 마지막 상품의 productId 가 127 이다
        server.enqueue(성공응답(상품_페이지_JSON))

        // When
        val 페이지 = repository.getProducts().getOrThrow()

        // Then
        assertEquals(127L, 페이지.nextCursor)
    }

    @Test
    fun `마지막 페이지의 nextCursor 가 null 로 와도 파싱이 깨지지 않는다`() = runTest {
        // Given: 마지막 페이지는 nextCursor 키가 남은 채 값만 null 이다
        server.enqueue(성공응답(마지막_상품_페이지_JSON))

        // When
        val 페이지 = repository.getProducts(cursor = 100L).getOrThrow()

        // Then
        assertNull(페이지.nextCursor)
    }

    @Test
    fun `마지막 페이지의 hasNext 는 false 다`() = runTest {
        // Given
        server.enqueue(성공응답(마지막_상품_페이지_JSON))

        // When
        val 페이지 = repository.getProducts(cursor = 100L).getOrThrow()

        // Then
        assertFalse(페이지.hasNext)
    }

    @Test
    fun `검색 응답은 페이지 래퍼 없이 배열이 그대로 data 에 온다`() = runTest {
        // Given: {"data": [ ... ]} — items·hasNext 가 없다(계약 §2-4, 전량 반환)
        server.enqueue(성공응답(검색_결과_JSON))

        // When
        val 결과 = repository.searchProducts(keyword = "아이폰").getOrThrow()

        // Then
        assertEquals(listOf(128L, 126L), 결과.map { it.productId })
    }

    // ────────────────────────────────────────────────────────────────
    // 들어오는 응답 — 이미지 URL / 알 수 없는 enum
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `thumbnailUrl 이 null 인 데모 상품은 도메인 썸네일도 null 이다`() = runTest {
        // Given: 시드·데모 데이터에는 이미지가 아예 없다(계약 §0.8)
        server.enqueue(성공응답(상품_페이지_JSON))

        // When
        val 첫상품 = repository.getProducts().getOrThrow().items.first()

        // Then: 임의의 더미 URL 로 채우면 화면이 '이미지 있음'으로 착각한다
        assertNull(첫상품.thumbnailUrl)
    }

    @Test
    fun `상세 응답의 imageUrls 가 빈 배열이면 도메인 이미지 목록도 비어 있다`() = runTest {
        // Given: 이미지 없는 상품은 [] 로 온다
        server.enqueue(성공응답(상품_상세_JSON))

        // When
        val 상세 = repository.getProductDetail(77L).getOrThrow()

        // Then
        assertEquals(emptyList<String>(), 상세.imageUrls)
    }

    @Test
    fun `상대 경로 이미지 주소는 baseUrl 이 붙은 절대 URL 로 변환된다`() = runTest {
        // Given: 서버는 "/api/products/images/{uuid}.jpg" 상대 경로만 준다
        server.enqueue(성공응답(이미지가_있는_상품_상세_JSON))

        // When
        val 상세 = repository.getProductDetail(78L).getOrThrow()

        // Then: 슬래시가 겹치지 않게 baseUrl 과 이어붙는다(그대로 Coil 에 넘길 수 있어야 한다)
        assertEquals(
            "${BuildConfig.BASE_URL}api/products/images/9c1e.jpg",
            상세.imageUrls.first(),
        )
    }

    @Test
    fun `이미 절대 URL 인 이미지 주소에는 baseUrl 을 덧붙이지 않는다`() = runTest {
        // Given: 서버가 URL 문자열을 검증 없이 저장해 절대 URL 이 섞여 있다(계약 §0.8)
        server.enqueue(성공응답(이미지가_있는_상품_상세_JSON))

        // When
        val 상세 = repository.getProductDetail(78L).getOrThrow()

        // Then
        assertEquals("https://example.com/old-2.jpg", 상세.imageUrls[1])
    }

    @Test
    fun `앱이 모르는 tradeStatus 문자열이 와도 예외 없이 UNKNOWN 으로 떨어진다`() = runTest {
        // Given: 백엔드가 나중에 상태를 추가한 상황(예: BLOCKED)
        server.enqueue(성공응답(모르는_상태값_상품_페이지_JSON))

        // When
        val 페이지 = repository.getProducts().getOrThrow()

        // Then: 목록 전체가 죽지 않고 그 카드의 배지만 사라진다
        assertEquals(TradeStatus.UNKNOWN, 페이지.items.first().tradeStatus)
    }

    // ────────────────────────────────────────────────────────────────
    // 들어오는 응답 — 에러 번역
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `상세 조회 404 PRODUCT_NOT_FOUND 는 AppError_Api 로 번역된다`() = runTest {
        // Given: 삭제·거래완료·판매자 탈퇴 상품은 목록에서 방금 봤어도 404 가 난다
        server.enqueue(에러응답(404, 상품없음_에러_JSON))

        // When
        val 결과 = repository.getProductDetail(999L)

        // Then: 예외가 화면까지 튀지 않고 Result.failure 에 담긴다
        val 오류 = 결과.exceptionOrNull() as AppError.Api
        assertEquals("PRODUCT_NOT_FOUND", 오류.code)
    }

    @Test
    fun `404 응답의 서버 문구가 사용자 문구로 그대로 전달된다`() = runTest {
        // Given
        server.enqueue(에러응답(404, 상품없음_에러_JSON))

        // When
        val 결과 = repository.getProductDetail(999L)

        // Then: UI 는 error 코드가 아니라 이 문장만 읽어서 띄운다
        assertEquals(
            "상품을 찾을 수 없습니다.",
            (결과.exceptionOrNull() as AppError).userMessage,
        )
    }

    @Test
    fun `숨김 상품 403 은 404 와 다른 status 로 구분된다`() = runTest {
        // Given: HIDDEN_PRODUCT 는 404 가 아니라 403 이다(계약 §3-1)
        server.enqueue(에러응답(403, 숨김상품_에러_JSON))

        // When
        val 결과 = repository.getProductDetail(80L)

        // Then
        assertEquals(403, (결과.exceptionOrNull() as AppError.Api).status)
    }

    // ────────────────────────────────────────────────────────────────
    // 응답 픽스처 — 계약 문서 §2-3·§2-4·§3-1 의 실제 JSON
    // ────────────────────────────────────────────────────────────────

    private fun 성공응답(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun 에러응답(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private val 빈_상품_페이지_JSON = """
        {
          "status": 200,
          "message": "요청이 성공적으로 처리되었습니다.",
          "data": { "items": [], "nextCursor": null, "hasNext": false }
        }
    """.trimIndent()

    private val 상품_페이지_JSON = """
        {
          "status": 200,
          "message": "요청이 성공적으로 처리되었습니다.",
          "data": {
            "items": [
              {
                "productId": 128,
                "memberId": 7,
                "categoryId": 1,
                "title": "아이폰 15 프로 256GB",
                "price": 800000.00,
                "tradeStatus": "ON_SALE",
                "region": "서울 강남구",
                "viewCount": 12,
                "favoriteCount": 3,
                "thumbnailUrl": null,
                "hidden": false
              },
              {
                "productId": 127,
                "memberId": 9,
                "categoryId": 2,
                "title": "LG 스탠바이미",
                "price": 550000.00,
                "tradeStatus": "RESERVED",
                "region": "서울 마포구",
                "viewCount": 41,
                "favoriteCount": 8,
                "thumbnailUrl": null,
                "hidden": false
              }
            ],
            "nextCursor": 127,
            "hasNext": true
          }
        }
    """.trimIndent()

    private val 마지막_상품_페이지_JSON = """
        {
          "status": 200,
          "message": "요청이 성공적으로 처리되었습니다.",
          "data": {
            "items": [
              {
                "productId": 1,
                "memberId": 3,
                "categoryId": 5,
                "title": "자전거 팝니다",
                "price": 120000.00,
                "tradeStatus": "ON_SALE",
                "region": "서울 강남구",
                "viewCount": 2,
                "favoriteCount": 0,
                "thumbnailUrl": null,
                "hidden": false
              }
            ],
            "nextCursor": null,
            "hasNext": false
          }
        }
    """.trimIndent()

    /** 목록·검색과 달리 `data` 가 **배열 그대로**다. 첫 원소 price 는 소수부 없는 800000. */
    private val 검색_결과_JSON = """
        {
          "status": 200,
          "message": "요청이 성공적으로 처리되었습니다.",
          "data": [
            {
              "productId": 128,
              "memberId": 7,
              "categoryId": 1,
              "title": "아이폰 15 프로 256GB",
              "price": 800000,
              "tradeStatus": "ON_SALE",
              "region": "서울 강남구",
              "viewCount": 12,
              "favoriteCount": 3,
              "thumbnailUrl": null,
              "hidden": false
            },
            {
              "productId": 126,
              "memberId": 4,
              "categoryId": 1,
              "title": "아이폰 케이스 나눔",
              "price": 0,
              "tradeStatus": "ON_SALE",
              "region": "서울 강남구",
              "viewCount": 5,
              "favoriteCount": 1,
              "thumbnailUrl": null,
              "hidden": false
            }
          ]
        }
    """.trimIndent()

    private val 상품_상세_JSON = """
        {
          "status": 200,
          "message": "요청이 성공적으로 처리되었습니다.",
          "data": {
            "productId": 77,
            "memberId": 7,
            "sellerNickname": "동네주민",
            "categoryId": 1,
            "title": "책상 스탠드",
            "description": "1년 사용했고 상태 좋습니다.",
            "price": 1234.56,
            "tradeStatus": "ON_SALE",
            "region": "서울 강남구",
            "viewCount": 13,
            "favoriteCount": 0,
            "thumbnailUrl": null,
            "imageUrls": [],
            "hidden": false
          }
        }
    """.trimIndent()

    private val 이미지가_있는_상품_상세_JSON = """
        {
          "status": 200,
          "message": "요청이 성공적으로 처리되었습니다.",
          "data": {
            "productId": 78,
            "memberId": 7,
            "sellerNickname": "동네주민",
            "categoryId": 1,
            "title": "캠핑 의자",
            "description": "접이식입니다.",
            "price": 30000.00,
            "tradeStatus": "ON_SALE",
            "region": "서울 마포구",
            "viewCount": 3,
            "favoriteCount": 0,
            "thumbnailUrl": "/api/products/images/9c1e.jpg",
            "imageUrls": ["/api/products/images/9c1e.jpg", "https://example.com/old-2.jpg"],
            "hidden": false
          }
        }
    """.trimIndent()

    private val 모르는_상태값_상품_페이지_JSON = """
        {
          "status": 200,
          "message": "요청이 성공적으로 처리되었습니다.",
          "data": {
            "items": [
              {
                "productId": 200,
                "memberId": 7,
                "categoryId": 1,
                "title": "신고 누적 상품",
                "price": 10000.00,
                "tradeStatus": "BLOCKED",
                "region": "서울 강남구",
                "viewCount": 0,
                "favoriteCount": 0,
                "thumbnailUrl": null,
                "hidden": false
              }
            ],
            "nextCursor": null,
            "hasNext": false
          }
        }
    """.trimIndent()

    private val 상품없음_에러_JSON = """
        {
          "status": 404,
          "error": "PRODUCT_NOT_FOUND",
          "message": "상품을 찾을 수 없습니다.",
          "timestamp": "2026-07-26T13:45:30.123456"
        }
    """.trimIndent()

    private val 숨김상품_에러_JSON = """
        {
          "status": 403,
          "error": "HIDDEN_PRODUCT",
          "message": "숨김 처리된 상품입니다.",
          "timestamp": "2026-07-26T13:45:30.123456"
        }
    """.trimIndent()

    // ══════════════════════ 상품 등록: 실제로 나가는 바이트 ══════════════════════
    //
    // 저장소 단위 테스트(ProductRepositoryImplTest)는 Retrofit 인터페이스를 목으로 두므로
    // **직렬화 이후**를 보지 못한다. `@Multipart` 애노테이션이 빠졌거나 DTO 필드 이름이
    // 서버와 어긋나도 그쪽 테스트는 전부 초록이다. 그 구간을 여기서만 잡을 수 있다.

    @Test
    fun `사진 업로드는 multipart 로 나가고 파트 이름이 files 다`() = runTest {
        // Given
        coEvery { imageCompressor.compressToJpeg(any()) } returns
            Result.success("가짜JPEG바이트".toByteArray())
        server.enqueue(성공응답(201, 이미지업로드_응답_JSON))
        server.enqueue(성공응답(201, 상품등록_응답_JSON))

        // When
        repository.createProduct(신규상품())

        // Then
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/products/images", request.path)

        val contentType = request.getHeader("Content-Type")
        assertTrue("multipart 여야 한다: $contentType", contentType!!.startsWith("multipart/form-data"))

        // 본문을 문자열로 읽어 파트 헤더를 확인한다.
        // 서버가 @RequestPart("files") 로 받으므로 이 이름이 다르면 파일이 도착하지 않는다.
        val body = request.body.readUtf8()
        assertTrue("파트 이름이 files 여야 한다", body.contains("name=\"files\""))
        assertTrue("파일 이름이 있어야 한다", body.contains("filename="))
        // 서버가 image/jpeg·png·gif·webp 화이트리스트로 검사한다.
        assertTrue("Content-Type 이 image/jpeg 여야 한다", body.contains("Content-Type: image/jpeg"))
        assertTrue("압축 결과가 본문에 실려야 한다", body.contains("가짜JPEG바이트"))
    }

    @Test
    fun `인증 토큰은 업로드와 등록 두 요청 모두에 붙는다`() = runTest {
        // Given: 등록은 인증 필수다(조회와 달리 익명 허용이 아니다)
        coEvery { imageCompressor.compressToJpeg(any()) } returns Result.success(byteArrayOf(1))
        server.enqueue(성공응답(201, 이미지업로드_응답_JSON))
        server.enqueue(성공응답(201, 상품등록_응답_JSON))

        // When
        repository.createProduct(신규상품())

        // Then: AuthInterceptor 가 두 요청 모두를 통과해야 한다.
        // 업로드에만 붙고 등록에 빠지면 사진은 올라갔는데 상품만 401 로 실패한다.
        assertEquals("Bearer test-access-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer test-access-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `상품 등록 본문의 필드 이름과 형식이 서버 계약과 일치한다`() = runTest {
        // Given
        coEvery { imageCompressor.compressToJpeg(any()) } returns Result.success(byteArrayOf(1))
        server.enqueue(성공응답(201, 이미지업로드_응답_JSON))
        server.enqueue(성공응답(201, 상품등록_응답_JSON))

        // When
        val result = repository.createProduct(
            신규상품(title = "닌텐도 스위치", price = BigDecimal("240000"), thumbnailIndex = 0),
        )

        // Then
        server.takeRequest() // 업로드 요청은 앞 테스트에서 검증했다
        val body = server.takeRequest().body.readUtf8()

        // 필드 이름 — 하나라도 다르면 서버가 null 로 읽고 400(또는 description 의 경우 500)이 난다
        assertTrue(body.contains("\"categoryId\":1"))
        assertTrue(body.contains("\"title\":\"닌텐도 스위치\""))
        assertTrue(body.contains("\"regionCode\":\"1168010300\""))
        assertTrue(body.contains("\"thumbnailIndex\":0"))

        // 업로드 응답이 준 경로가 그대로 실려야 한다(절대 URL 로 바꾸면 그 URL 이 DB 에 저장된다)
        assertTrue(body.contains("\"imageUrls\":[\"/api/products/images/abc-123.jpg\"]"))

        // 가격은 **문자열이 아니라 JSON 숫자**여야 한다.
        // "240000" 으로 나가면 서버 Jackson 이 BigDecimal 로 못 읽어 price=null → 400 INVALID_PRODUCT_PRICE.
        assertTrue("가격이 숫자로 나가야 한다: $body", body.contains("\"price\":240000"))
        assertFalse("가격이 문자열이면 안 된다: $body", body.contains("\"price\":\"240000\""))

        assertEquals(777L, result.getOrNull())
    }

    @Test
    fun `설명을 비우면 빈 문자열 키가 본문에 남는다`() = runTest {
        // Given
        coEvery { imageCompressor.compressToJpeg(any()) } returns Result.success(byteArrayOf(1))
        server.enqueue(성공응답(201, 이미지업로드_응답_JSON))
        server.enqueue(성공응답(201, 상품등록_응답_JSON))

        // When
        repository.createProduct(신규상품(description = ""))

        // Then: 이 테스트가 잡는 것은 **키가 통째로 사라지는** 사고다.
        // 앱 Json 은 explicitNulls=false 라 null 이면 키가 빠지고, 서버는 그걸
        // 검증 없이 역참조해 400 이 아니라 500 을 낸다.
        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertTrue("description 키가 남아야 한다: $body", body.contains("\"description\":\"\""))
    }

    @Test
    fun `사진이 여러 장이면 업로드 요청도 그만큼 나간다`() = runTest {
        // Given: 3장
        coEvery { imageCompressor.compressToJpeg(any()) } returns Result.success(byteArrayOf(1))
        repeat(3) { server.enqueue(성공응답(201, 이미지업로드_응답_JSON)) }
        server.enqueue(성공응답(201, 상품등록_응답_JSON))

        // When
        repository.createProduct(신규상품(imageUris = listOf("A", "B", "C")))

        // Then: 서버의 max-request-size 가 5MB 라 한 요청에 몰아 담을 수 없다.
        // 요청이 4번(업로드 3 + 등록 1) 나가는 것이 정상이다.
        assertEquals(4, server.requestCount)
        repeat(3) { assertEquals("/api/products/images", server.takeRequest().path) }
        assertEquals("/api/products", server.takeRequest().path)
    }

    @Test
    fun `등록에 실패하면 서버 메시지가 그대로 사용자에게 전달된다`() = runTest {
        // Given: 사진은 올라갔지만 카테고리가 없다
        coEvery { imageCompressor.compressToJpeg(any()) } returns Result.success(byteArrayOf(1))
        server.enqueue(성공응답(201, 이미지업로드_응답_JSON))
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody(카테고리없음_에러_JSON),
        )

        // When
        val result = repository.createProduct(신규상품())

        // Then: ErrorCode 이름(CATEGORY_NOT_FOUND)이 아니라 사람이 읽는 message 가 화면으로 간다
        val error = result.exceptionOrNull()
        assertTrue(error is AppError.Api)
        assertEquals(404, (error as AppError.Api).status)
        assertEquals("카테고리를 찾을 수 없습니다.", error.userMessage)
    }

    private fun 성공응답(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun 신규상품(
        title: String = "닌텐도 스위치",
        description: String = "작년에 샀어요",
        price: BigDecimal = BigDecimal("240000"),
        categoryId: Long = 1L,
        regionCode: String = "1168010300",
        imageUris: List<String> = listOf("content://media/1"),
        thumbnailIndex: Int = 0,
    ) = NewProduct(
        title = title,
        description = description,
        price = price,
        categoryId = categoryId,
        regionCode = regionCode,
        imageUris = imageUris,
        thumbnailIndex = thumbnailIndex,
    )

    private val 이미지업로드_응답_JSON = """
        {
          "status": 201,
          "message": "이미지 업로드 성공",
          "data": {
            "imageUrls": ["/api/products/images/abc-123.jpg"]
          }
        }
    """.trimIndent()

    private val 상품등록_응답_JSON = """
        {
          "status": 201,
          "message": "상품 등록 성공",
          "data": {
            "productId": 777,
            "memberId": 2,
            "sellerNickname": "동네주민",
            "categoryId": 1,
            "title": "닌텐도 스위치",
            "description": "작년에 샀어요",
            "price": 240000.00,
            "tradeStatus": "ON_SALE",
            "regionCode": "1168010300",
            "regionName": "역삼동",
            "regionFullName": "서울특별시 강남구 역삼동",
            "viewCount": 0,
            "favoriteCount": 0,
            "thumbnailUrl": "/api/products/images/abc-123.jpg",
            "imageUrls": ["/api/products/images/abc-123.jpg"],
            "hidden": false
          }
        }
    """.trimIndent()

    private val 카테고리없음_에러_JSON = """
        {
          "status": 404,
          "error": "CATEGORY_NOT_FOUND",
          "message": "카테고리를 찾을 수 없습니다.",
          "timestamp": "2026-08-03T10:00:00.000000"
        }
    """.trimIndent()
}
