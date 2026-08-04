package com.dongnemarket.mobile.data.repository

import com.dongnemarket.mobile.data.image.ImageCompressor
import com.dongnemarket.mobile.data.remote.ProductApiService
import com.dongnemarket.mobile.data.remote.dto.ApiEnvelope
import com.dongnemarket.mobile.data.remote.dto.ProductCreateRequestDto
import com.dongnemarket.mobile.data.remote.dto.ProductImageUploadResponse
import com.dongnemarket.mobile.data.remote.dto.ProductPageResponse
import com.dongnemarket.mobile.data.remote.dto.ProductResponse
import com.dongnemarket.mobile.data.remote.dto.ProductSummaryResponse
import com.dongnemarket.mobile.domain.model.AppError
import com.dongnemarket.mobile.domain.model.NewProduct
import com.dongnemarket.mobile.domain.model.TradeStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal

/**
 * 상품 리포지토리 명세 — "요청을 어떻게 다듬어 보내고, 응답·실패를 어떻게 번역하는가".
 *
 * 여기서 지켜야 하는 서버 사실 두 가지:
 *  - `regions` 는 **최대 2개**다. 3개 이상 보내면 400 `INVALID_INPUT_VALUE` 다(계약 §7-19).
 *  - 실패는 **예외로 던지지 않는다.** 항상 `Result.failure(AppError)` 로 나와야
 *    ViewModel 이 `onFailure { it.userMessage }` 만 보고 화면을 그릴 수 있다.
 */
class ProductRepositoryImplTest {

    private val api = mockk<ProductApiService>()

    /**
     * 압축기는 `Bitmap`·`ContentResolver` 를 쓰는 Android 프레임워크 코드라 JVM 에서 실행되지 않는다.
     * 등록 테스트는 "압축 결과가 이 바이트다" 라고 가정하고 **그 뒤의 조립**(파트 이름·요청 순서·
     * 실패 전파)을 검증한다. 압축 자체의 정확성은 실기 검수의 몫이다.
     */
    private val imageCompressor = mockk<ImageCompressor>()
    private val repository = ProductRepositoryImpl(api, imageCompressor)

    // ────────────────────────── 홈 목록: 응답 매핑 ──────────────────────────

    @Test
    fun `홈 목록 조회에 성공하면 응답 items 가 도메인 상품 목록이 된다`() = runTest {
        // Given — 서버가 상품 2건을 id DESC 로 내려준다
        coEvery { api.getProducts(any(), any(), any()) } returns envelope(
            ProductPageResponse(
                items = listOf(
                    summaryResponse(productId = 103L, title = "따릉이 자전거"),
                    summaryResponse(productId = 101L, title = "아이폰 15 프로"),
                ),
                nextCursor = 101L,
                hasNext = true,
            ),
        )

        // When
        val page = repository.getProducts().getOrThrow()

        // Then
        assertEquals(listOf("따릉이 자전거", "아이폰 15 프로"), page.items.map { it.title })
    }

    @Test
    fun `다음 페이지가 있으면 nextCursor 가 도메인 페이지에 그대로 실린다`() = runTest {
        // Given
        coEvery { api.getProducts(any(), any(), any()) } returns envelope(
            ProductPageResponse(
                items = listOf(summaryResponse(productId = 101L)),
                nextCursor = 101L,
                hasNext = true,
            ),
        )

        // When
        val page = repository.getProducts().getOrThrow()

        // Then — 무한스크롤이 이 값을 다음 요청 cursor 로 쓴다
        assertEquals(101L, page.nextCursor)
    }

    @Test
    fun `마지막 페이지면 hasNext 가 false 로 내려와 무한스크롤이 멈춘다`() = runTest {
        // Given — 서버는 마지막 페이지에서도 nextCursor 키를 남긴 채 null 로 준다
        coEvery { api.getProducts(any(), any(), any()) } returns envelope(
            ProductPageResponse(items = emptyList(), nextCursor = null, hasNext = false),
        )

        // When
        val page = repository.getProducts().getOrThrow()

        // Then
        assertFalse(page.hasNext)
    }

    @Test
    fun `목록 상품의 문자열 상태값도 리포지토리를 통과하면 enum 이 되어 있다`() = runTest {
        // Given
        coEvery { api.getProducts(any(), any(), any()) } returns envelope(
            ProductPageResponse(items = listOf(summaryResponse(tradeStatus = "RESERVED"))),
        )

        // When
        val page = repository.getProducts().getOrThrow()

        // Then — UI 는 DTO 의 String 을 절대 보지 않는다
        assertEquals(TradeStatus.RESERVED, page.items.single().tradeStatus)
    }

    // ────────────────────────── 홈 목록: regions 방어 ──────────────────────────

    @Test
    fun `내 동네가 3개면 서버에는 앞의 2개만 전달된다`() = runTest {
        // Given — 3개 이상이면 서버가 400 을 낸다. 화면을 에러로 덮는 대신 앞의 2개로 조회한다.
        var sentRegions: List<String>? = listOf("호출되지 않음")
        coEvery { api.getProducts(any(), any(), any()) } answers {
            sentRegions = firstArg<List<String>?>()
            envelope(ProductPageResponse())
        }

        // When
        repository.getProducts(regionCodes = listOf("서울 강남구", "서울 마포구", "서울 성동구"))

        // Then
        assertEquals(listOf("서울 강남구", "서울 마포구"), sentRegions)
    }

    @Test
    fun `중복된 동네는 제거된 뒤 전달된다`() = runTest {
        // Given — 중복이 섞이면 상한 2개를 중복만으로 다 써 버린다
        var sentRegions: List<String>? = listOf("호출되지 않음")
        coEvery { api.getProducts(any(), any(), any()) } answers {
            sentRegions = firstArg<List<String>?>()
            envelope(ProductPageResponse())
        }

        // When
        repository.getProducts(regionCodes = listOf("서울 강남구", "서울 강남구", "서울 마포구"))

        // Then
        assertEquals(listOf("서울 강남구", "서울 마포구"), sentRegions)
    }

    @Test
    fun `동네 목록이 비어 있으면 regions 파라미터를 아예 보내지 않는다`() = runTest {
        // Given — 빈 리스트를 그대로 보내면 "regions=" 라는 무의미한 파라미터가 붙는다
        var sentRegions: List<String>? = listOf("호출되지 않음")
        coEvery { api.getProducts(any(), any(), any()) } answers {
            sentRegions = firstArg<List<String>?>()
            envelope(ProductPageResponse())
        }

        // When
        repository.getProducts(regionCodes = emptyList())

        // Then — null 이면 Retrofit 이 파라미터 자체를 뺀다 = 전국 조회
        assertNull(sentRegions)
    }

    @Test
    fun `동네 목록이 공백 원소뿐이면 전국 조회로 떨어진다`() = runTest {
        // Given
        var sentRegions: List<String>? = listOf("호출되지 않음")
        coEvery { api.getProducts(any(), any(), any()) } answers {
            sentRegions = firstArg<List<String>?>()
            envelope(ProductPageResponse())
        }

        // When
        repository.getProducts(regionCodes = listOf("", "   "))

        // Then
        assertNull(sentRegions)
    }

    @Test
    fun `동네를 지정하지 않으면 regions 없이 전국을 조회한다`() = runTest {
        // Given
        var sentRegions: List<String>? = listOf("호출되지 않음")
        coEvery { api.getProducts(any(), any(), any()) } answers {
            sentRegions = firstArg<List<String>?>()
            envelope(ProductPageResponse())
        }

        // When
        repository.getProducts(regionCodes = null)

        // Then
        assertNull(sentRegions)
    }

    @Test
    fun `size 가 서버 상한 100 을 넘으면 100 으로 깎여 전달된다`() = runTest {
        // Given
        var sentSize = -1
        coEvery { api.getProducts(any(), any(), any()) } answers {
            sentSize = arg<Int>(2)
            envelope(ProductPageResponse())
        }

        // When
        repository.getProducts(size = 500)

        // Then
        assertEquals(100, sentSize)
    }

    @Test
    fun `첫 페이지 요청에는 cursor 를 보내지 않는다`() = runTest {
        // Given — 서버는 cursor 를 "id < cursor" 로 쓰므로 첫 페이지에는 있으면 안 된다
        var sentCursor: Long? = -1L
        coEvery { api.getProducts(any(), any(), any()) } answers {
            sentCursor = arg<Long?>(1)
            envelope(ProductPageResponse())
        }

        // When
        repository.getProducts(cursor = null)

        // Then
        assertNull(sentCursor)
    }

    // ────────────────────────── 검색 ──────────────────────────

    @Test
    fun `검색 결과는 페이징 래퍼 없이 도메인 상품 리스트가 된다`() = runTest {
        // Given — search 응답은 배열이 그대로 온다(페이징 없음)
        coEvery { api.searchProducts(any(), any(), any()) } returns envelope(
            listOf(
                summaryResponse(productId = 101L, title = "아이폰 15 프로"),
                summaryResponse(productId = 102L, title = "아이폰 케이스"),
            ),
        )

        // When
        val products = repository.searchProducts(keyword = "아이폰").getOrThrow()

        // Then
        assertEquals(listOf(101L, 102L), products.map { it.productId })
    }

    @Test
    fun `검색어 앞뒤 공백은 제거하고 보낸다`() = runTest {
        // Given
        var sentKeyword: String? = "호출되지 않음"
        coEvery { api.searchProducts(any(), any(), any()) } answers {
            sentKeyword = firstArg<String?>()
            envelope(emptyList<ProductSummaryResponse>())
        }

        // When
        repository.searchProducts(keyword = "  아이폰  ")

        // Then
        assertEquals("아이폰", sentKeyword)
    }

    @Test
    fun `검색어가 공백뿐이면 keyword 파라미터를 보내지 않는다`() = runTest {
        // Given — 빈 문자열을 보내면 "제목에 ''를 포함"이라는 무의미한 조건이 붙는다
        var sentKeyword: String? = "호출되지 않음"
        coEvery { api.searchProducts(any(), any(), any()) } answers {
            sentKeyword = firstArg<String?>()
            envelope(emptyList<ProductSummaryResponse>())
        }

        // When
        repository.searchProducts(keyword = "   ")

        // Then
        assertNull(sentKeyword)
    }

    @Test
    fun `검색에서도 동네는 최대 2개까지만 전달된다`() = runTest {
        // Given
        var sentRegions: List<String>? = listOf("호출되지 않음")
        coEvery { api.searchProducts(any(), any(), any()) } answers {
            sentRegions = arg<List<String>?>(2)
            envelope(emptyList<ProductSummaryResponse>())
        }

        // When
        repository.searchProducts(
            categoryId = 1L,
            regionCodes = listOf("서울 강남구", "서울 마포구", "서울 성동구"),
        )

        // Then
        assertEquals(listOf("서울 강남구", "서울 마포구"), sentRegions)
    }

    @Test
    fun `존재하지 않는 카테고리로 검색하면 에러가 아니라 빈 목록이 온다`() = runTest {
        // Given — 서버는 없는 categoryId 를 에러로 보지 않고 0건을 준다
        coEvery { api.searchProducts(any(), any(), any()) } returns
            envelope(emptyList<ProductSummaryResponse>())

        // When
        val products = repository.searchProducts(categoryId = 9999L).getOrThrow()

        // Then
        assertTrue(products.isEmpty())
    }

    // ────────────────────────── 상세 ──────────────────────────

    @Test
    fun `상세 조회 성공하면 판매자 닉네임까지 담긴 ProductDetail 이 된다`() = runTest {
        // Given
        coEvery { api.getProductDetail(101L) } returns envelope(
            productResponse(productId = 101L, sellerNickname = "동네주민"),
        )

        // When
        val detail = repository.getProductDetail(101L).getOrThrow()

        // Then
        assertEquals("동네주민", detail.sellerNickname)
    }

    @Test
    fun `상세의 상대경로 이미지들은 리포지토리를 통과하면 절대 URL 이 되어 있다`() = runTest {
        // Given
        coEvery { api.getProductDetail(101L) } returns envelope(
            productResponse(imageUrls = listOf("/api/products/images/a.jpg")),
        )

        // When
        val detail = repository.getProductDetail(101L).getOrThrow()

        // Then — UI 는 상대경로를 볼 일이 없어야 한다
        assertTrue(detail.imageUrls.single().startsWith("http"))
    }

    // ────────────────────────── 실패 번역 ──────────────────────────

    @Test
    fun `인터넷이 끊기면 예외가 밖으로 던져지지 않고 Network 오류 실패로 돌아온다`() = runTest {
        // Given
        coEvery { api.getProducts(any(), any(), any()) } throws IOException("timeout")

        // When — 예외가 튀어 오르면 이 줄에서 테스트가 깨진다
        val result = repository.getProducts()

        // Then
        assertTrue(result.exceptionOrNull() is AppError.Network)
    }

    @Test
    fun `네트워크 실패의 안내 문구는 화면에 그대로 띄울 수 있는 한국어다`() = runTest {
        // Given
        coEvery { api.getProducts(any(), any(), any()) } throws IOException("timeout")

        // When
        val error = repository.getProducts().exceptionOrNull() as AppError

        // Then
        assertEquals("네트워크 연결을 확인해 주세요.", error.userMessage)
    }

    @Test
    fun `삭제된 상품을 상세 조회하면 404 응답이 Api 오류로 번역된다`() = runTest {
        // Given — 목록에서 방금 본 상품도 상세에서는 404 가 날 수 있다
        coEvery { api.getProductDetail(101L) } throws httpException(
            code = 404,
            body = """{"status":404,"error":"PRODUCT_NOT_FOUND",""" +
                """"message":"상품을 찾을 수 없습니다.","timestamp":"2026-07-27T10:00:00"}""",
        )

        // When
        val error = repository.getProductDetail(101L).exceptionOrNull()

        // Then
        assertEquals(404, (error as AppError.Api).status)
    }

    @Test
    fun `404 응답의 백엔드 error 코드는 분기용으로 보존된다`() = runTest {
        // Given
        coEvery { api.getProductDetail(101L) } throws httpException(
            code = 404,
            body = """{"status":404,"error":"PRODUCT_NOT_FOUND",""" +
                """"message":"상품을 찾을 수 없습니다.","timestamp":"2026-07-27T10:00:00"}""",
        )

        // When
        val error = repository.getProductDetail(101L).exceptionOrNull() as AppError.Api

        // Then — 화면에 노출하지는 않지만 로그·분기에 쓴다
        assertEquals("PRODUCT_NOT_FOUND", error.code)
    }

    @Test
    fun `서버가 준 한국어 message 가 사용자 안내 문구로 쓰인다`() = runTest {
        // Given
        coEvery { api.getProductDetail(101L) } throws httpException(
            code = 403,
            body = """{"status":403,"error":"HIDDEN_PRODUCT",""" +
                """"message":"숨김 처리된 상품입니다.","timestamp":"2026-07-27T10:00:00"}""",
        )

        // When
        val error = repository.getProductDetail(101L).exceptionOrNull() as AppError

        // Then
        assertEquals("숨김 처리된 상품입니다.", error.userMessage)
    }

    @Test
    fun `에러 본문이 계약과 다른 형태여도 기본 안내 문구로 대체된다`() = runTest {
        // Given — 매핑 없는 URL 의 404 는 ApiErrorBody 형식이 아니다(계약 §7-23)
        coEvery { api.getProductDetail(101L) } throws httpException(
            code = 404,
            body = "<html>Not Found</html>",
        )

        // When
        val error = repository.getProductDetail(101L).exceptionOrNull() as AppError

        // Then — 파싱 실패로 앱이 죽지 않고 기본 문구가 나온다
        assertEquals("요청을 처리할 수 없습니다.", error.userMessage)
    }

    @Test
    fun `401 이 오면 로그인 화면으로 보낼 수 있게 Unauthorized 로 번역된다`() = runTest {
        // Given
        coEvery { api.getProductDetail(101L) } throws httpException(
            code = 401,
            body = """{"status":401,"error":"INVALID_TOKEN",""" +
                """"message":"유효하지 않은 토큰입니다.","timestamp":"2026-07-27T10:00:00"}""",
        )

        // When
        val error = repository.getProductDetail(101L).exceptionOrNull()

        // Then
        assertTrue(error is AppError.Unauthorized)
    }

    @Test
    fun `껍데기는 200 인데 data 가 없으면 계약 위반으로 보고 EmptyBody 실패가 된다`() = runTest {
        // Given
        coEvery { api.getProducts(any(), any(), any()) } returns
            ApiEnvelope(status = 200, message = "요청이 성공적으로 처리되었습니다.", data = null)

        // When
        val error = repository.getProducts().exceptionOrNull()

        // Then
        assertTrue(error is AppError.EmptyBody)
    }

    // ────────────────────────── 테스트용 응답 조립 ──────────────────────────

    private fun <T : Any> envelope(data: T) =
        ApiEnvelope(status = 200, message = "요청이 성공적으로 처리되었습니다.", data = data)

    private fun httpException(code: Int, body: String) = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())),
    )

    /** 계약 §5.4 `ProductSummaryResponse` 필드 구성 그대로. */
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

    /** 계약 §5.4 `ProductResponse` 필드 구성 그대로. */
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

    // ══════════════════════ 상품 등록: 2단계 조립 ══════════════════════
    //
    // 조회 함수들과 달리 등록은 `apiCall {}` 한 줄이 아니다.
    // 서버에 "이미지까지 한 번에" 받는 엔드포인트가 없어서 저장소가 두 요청을 순서대로 엮는다:
    //   ① POST /api/products/images  (장당 1회)  → 경로 수집
    //   ② POST /api/products         (수집한 경로 전부)
    // 그 조립이 여기서 검증하는 대상이다.

    @Test
    fun `등록하면 사진을 한 장씩 올린 뒤 그 경로로 상품을 만든다`() = runTest {
        // Given: 사진 2장. 압축기는 장마다 다른 바이트를 준다(순서 섞임을 잡기 위해).
        coEvery { imageCompressor.compressToJpeg("uri-A") } returns Result.success(byteArrayOf(1))
        coEvery { imageCompressor.compressToJpeg("uri-B") } returns Result.success(byteArrayOf(2))
        coEvery { api.uploadImages(any()) } returnsMany listOf(
            envelope(ProductImageUploadResponse(listOf("/api/products/images/a.jpg"))),
            envelope(ProductImageUploadResponse(listOf("/api/products/images/b.jpg"))),
        )
        val 등록요청 = slot<ProductCreateRequestDto>()
        coEvery { api.createProduct(capture(등록요청)) } returns
            envelope(productResponse(productId = 501L))

        // When
        val result = repository.createProduct(신규상품(imageUris = listOf("uri-A", "uri-B")))

        // Then: 업로드는 **장당 한 번씩** 나가야 한다.
        // 서버의 max-request-size 가 max-file-size 와 똑같이 5MB 라, 여러 장을 한 요청에 담으면
        // 파일 검증에 닿기도 전에 요청 자체가 잘린다.
        coVerify(exactly = 2) { api.uploadImages(any()) }
        assertEquals(
            listOf("/api/products/images/a.jpg", "/api/products/images/b.jpg"),
            등록요청.captured.imageUrls,
        )
        assertEquals(501L, result.getOrNull())
    }

    @Test
    fun `업로드 파트 이름은 files 이고 타입은 image_jpeg 다`() = runTest {
        // Given
        coEvery { imageCompressor.compressToJpeg(any()) } returns Result.success(byteArrayOf(1, 2, 3))
        val 파트 = slot<List<MultipartBody.Part>>()
        coEvery { api.uploadImages(capture(파트)) } returns
            envelope(ProductImageUploadResponse(listOf("/api/products/images/a.jpg")))
        coEvery { api.createProduct(any()) } returns envelope(productResponse())

        // When
        repository.createProduct(신규상품(imageUris = listOf("uri-A")))

        // Then: 서버는 @RequestPart("files") 로 받는다.
        // 이름이 다르면 404 도 400 도 아니라 "파일이 비었다"는 검증 실패로 나타나 원인 추적이 어렵다.
        val 헤더 = 파트.captured.single().headers?.get("Content-Disposition")
        assertTrue("파트 이름이 files 여야 한다: $헤더", 헤더!!.contains("name=\"files\""))

        // 서버가 image/jpeg·png·gif·webp 화이트리스트로 검사하므로
        // 타입을 비워 두면(application/octet-stream) 무조건 거부된다.
        assertEquals("image/jpeg", 파트.captured.single().body.contentType().toString())
    }

    @Test
    fun `사진 한 장이 올라갈 때마다 진행률을 알려 준다`() = runTest {
        // Given: 사진 3장
        coEvery { imageCompressor.compressToJpeg(any()) } returns Result.success(byteArrayOf(1))
        coEvery { api.uploadImages(any()) } returns
            envelope(ProductImageUploadResponse(listOf("/img.jpg")))
        coEvery { api.createProduct(any()) } returns envelope(productResponse())
        val 진행률 = mutableListOf<Pair<Int, Int>>()

        // When
        repository.createProduct(신규상품(imageUris = listOf("A", "B", "C"))) { done, total ->
            진행률 += done to total
        }

        // Then: 사진 여러 장은 수 초가 걸린다. 진행률이 없으면 화면이 멈춘 것처럼 보인다.
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), 진행률)
    }

    @Test
    fun `업로드가 중간에 실패하면 상품을 만들지 않는다`() = runTest {
        // Given: 2장 중 두 번째 업로드가 실패한다
        coEvery { imageCompressor.compressToJpeg(any()) } returns Result.success(byteArrayOf(1))
        coEvery { api.uploadImages(any()) } returns
            envelope(ProductImageUploadResponse(listOf("/api/products/images/a.jpg"))) andThenThrows
            IOException("업로드 중 연결 끊김")

        // When
        val result = repository.createProduct(신규상품(imageUris = listOf("A", "B")))

        // Then: 여기서 상품을 만들어 버리면 **사진 한 장이 빠진 상품**이 등록된다.
        // 사용자는 다시 올릴 방법이 없고(수정 화면 없음) 목록에는 반쪽짜리 상품이 남는다.
        coVerify(exactly = 0) { api.createProduct(any()) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AppError.Network)
    }

    @Test
    fun `사진 압축에 실패하면 아무것도 올리지 않고 멈춘다`() = runTest {
        // Given: 사용자가 고른 사진이 그 사이 삭제됐거나 읽을 수 없다
        coEvery { imageCompressor.compressToJpeg("A") } returns Result.success(byteArrayOf(1))
        coEvery { imageCompressor.compressToJpeg("B") } returns
            Result.failure(IOException("사진에 접근할 수 없습니다."))
        coEvery { api.uploadImages(any()) } returns
            envelope(ProductImageUploadResponse(listOf("/img.jpg")))
        coEvery { api.createProduct(any()) } returns envelope(productResponse())

        // When
        val result = repository.createProduct(신규상품(imageUris = listOf("A", "B")))

        // Then: A 는 이미 올라갔지만 상품은 만들지 않는다(고아 파일은 남는다 — 지울 API 가 없다).
        coVerify(exactly = 1) { api.uploadImages(any()) }
        coVerify(exactly = 0) { api.createProduct(any()) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `올린 사진 수와 받은 경로 수가 다르면 등록을 중단한다`() = runTest {
        // Given: 한 장을 올렸는데 서버가 경로를 두 개 줬다(계약 위반)
        coEvery { imageCompressor.compressToJpeg(any()) } returns Result.success(byteArrayOf(1))
        coEvery { api.uploadImages(any()) } returns
            envelope(ProductImageUploadResponse(listOf("/a.jpg", "/b.jpg")))
        coEvery { api.createProduct(any()) } returns envelope(productResponse())

        // When
        val result = repository.createProduct(신규상품(imageUris = listOf("A")))

        // Then: 개수가 어긋난 채 진행하면 thumbnailIndex 가 엉뚱한 사진을 가리키게 된다.
        // "대표로 고른 사진과 다른 사진이 목록에 뜨는" 조용한 오류다.
        coVerify(exactly = 0) { api.createProduct(any()) }
        assertTrue(result.exceptionOrNull() is AppError.EmptyBody)
    }

    @Test
    fun `설명을 비워도 null 이 아니라 빈 문자열로 실린다`() = runTest {
        // Given
        coEvery { imageCompressor.compressToJpeg(any()) } returns Result.success(byteArrayOf(1))
        coEvery { api.uploadImages(any()) } returns
            envelope(ProductImageUploadResponse(listOf("/img.jpg")))
        val 등록요청 = slot<ProductCreateRequestDto>()
        coEvery { api.createProduct(capture(등록요청)) } returns envelope(productResponse())

        // When
        repository.createProduct(신규상품(description = ""))

        // Then: 서버 ProductService 는 title·price·imageUrls 만 검증한 뒤
        // `Product.create(..., request.description!!, ...)` 로 description 을 **검증 없이 역참조**한다.
        // 즉 null 이면 400 이 아니라 NPE → 500 이다.
        // 게다가 앱 Json 은 explicitNulls=false 라 null 필드는 키째 사라져 그 경로를 정확히 밟는다.
        assertEquals("", 등록요청.captured.description)
    }

    @Test
    fun `제목과 설명의 앞뒤 공백은 보내기 전에 다듬는다`() = runTest {
        // Given
        coEvery { imageCompressor.compressToJpeg(any()) } returns Result.success(byteArrayOf(1))
        coEvery { api.uploadImages(any()) } returns
            envelope(ProductImageUploadResponse(listOf("/img.jpg")))
        val 등록요청 = slot<ProductCreateRequestDto>()
        coEvery { api.createProduct(capture(등록요청)) } returns envelope(productResponse())

        // When: 키보드 자동완성이 뒤에 공백을 붙이는 일이 흔하다
        repository.createProduct(신규상품(title = "  닌텐도 스위치  ", description = "  깨끗해요  "))

        // Then: 지역 코드와 달리 제목은 서버가 완전 비교하지 않으므로 다듬어도 안전하다
        assertEquals("닌텐도 스위치", 등록요청.captured.title)
        assertEquals("깨끗해요", 등록요청.captured.description)
    }

    @Test
    fun `상품 생성이 실패하면 사진은 올라갔어도 실패로 돌아온다`() = runTest {
        // Given: 업로드는 다 됐는데 마지막 등록에서 서버가 거부했다
        coEvery { imageCompressor.compressToJpeg(any()) } returns Result.success(byteArrayOf(1))
        coEvery { api.uploadImages(any()) } returns
            envelope(ProductImageUploadResponse(listOf("/img.jpg")))
        coEvery { api.createProduct(any()) } throws HttpException(
            Response.error<Any>(
                404,
                """{"status":404,"error":"CATEGORY_NOT_FOUND","message":"카테고리를 찾을 수 없습니다."}"""
                    .toResponseBody("application/json".toMediaType()),
            ),
        )

        // When
        val result = repository.createProduct(신규상품())

        // Then: 실패는 예외가 아니라 Result 로 온다(다른 함수들과 같은 규약).
        val error = result.exceptionOrNull()
        assertTrue(error is AppError.Api)
        assertEquals(404, (error as AppError.Api).status)
        assertEquals("카테고리를 찾을 수 없습니다.", error.userMessage)
    }

    /** 등록 입력 픽스처. 인자로 준 것만 바꿔 "그 값 때문에 결과가 달라졌다"를 분명히 한다. */
    private fun 신규상품(
        title: String = "닌텐도 스위치",
        description: String = "작년에 샀어요",
        price: BigDecimal = BigDecimal("240000"),
        categoryId: Long = 1L,
        regionCode: String = "1168010300",
        imageUris: List<String> = listOf("uri-A"),
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
}
