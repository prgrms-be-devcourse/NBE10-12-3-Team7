package com.dongnemarket.mobile.data.remote

import com.dongnemarket.mobile.data.remote.dto.ApiEnvelope
import com.dongnemarket.mobile.data.remote.dto.ProductPageResponse
import com.dongnemarket.mobile.data.remote.dto.ProductResponse
import com.dongnemarket.mobile.data.remote.dto.ProductSummaryResponse
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 상품 관련 HTTP 호출 선언부. Retrofit 이 이 인터페이스를 읽어 구현체를 런타임에 만들어 준다
 * (Spring Cloud OpenFeign 의 `@FeignClient` 인터페이스와 같은 자리).
 *
 * 규칙 2개:
 * - 경로는 선행 `/` 없이 `api/...` 로 쓴다. 선행 `/` 를 쓰면 baseUrl 의 path 가 잘린다.
 * - 반환 타입은 껍데기째(`ApiEnvelope<...>`) 받는다. 껍데기를 벗기는 일은 `apiCall {}` 이 한다.
 *
 * `Authorization` 헤더는 [AuthInterceptor] 가 자동으로 붙이므로 `@Header` 를 쓰지 않는다.
 * (상품 조회는 애초에 무인증 허용이라 토큰이 없어도 200 이 온다.)
 */
interface ProductApiService {

    /**
     * 홈 목록 — 커서 페이징.
     *
     * `regionCodes` 를 `List<String>` 으로 선언하면 Retrofit 이 **반복 파라미터**로 직렬화한다:
     * `?regionCodes=11680&regionCodes=11440` (콤마 join 이 아니다 — 서버가 기대하는 형태가 이것이다).
     * null 이면 파라미터 자체가 빠진다.
     *
     * ### ⚠️ 파라미터 이름이 `regions` → `regionCodes` 로 바뀌었다 (2026-07)
     * 값도 **이름 문자열이 아니라 지역 코드**다. 옛 이름으로 보내면 서버가 그 파라미터를 읽지 않아
     * **에러 없이 필터가 통째로 무시**된다(항상 전국 조회). 예외도 로그도 남지 않는 유형이라
     * "동네 설정했는데 왜 다른 지역 상품이 뜨지?"로만 드러난다.
     */
    @GET("api/products")
    suspend fun getProducts(
        @Query("regionCodes") regionCodes: List<String>? = null,
        @Query("cursor") cursor: Long? = null,
        @Query("size") size: Int = 30,
    ): ApiEnvelope<ProductPageResponse>

    /**
     * 검색 + 카테고리/지역 필터. **응답에 페이징 래퍼가 없고 배열이 그대로 온다.**
     *
     * 요청 DTO 클래스를 만들지 않는 이유: 서버 컨트롤러가 body 가 아니라
     * 쿼리 파라미터를 하나씩 받아 조립하기 때문이다.
     * (계약상 `minPrice`/`maxPrice`/`tradeStatus` 도 받지만 Phase 1 화면에서 쓰지 않아 선언하지 않았다.)
     */
    @GET("api/products/search")
    suspend fun searchProducts(
        @Query("keyword") keyword: String? = null,
        @Query("categoryId") categoryId: Long? = null,
        /** 지역 **코드**. 목록과 같은 이유로 `regions` 가 아니라 `regionCodes` 다. */
        @Query("regionCodes") regionCodes: List<String>? = null,
    ): ApiEnvelope<List<ProductSummaryResponse>>

    /**
     * 상세 조회. ⚠️ 서버에서 조회수를 +1 하는 **쓰기 동작**이다 — 호출 횟수를 아껴라.
     */
    @GET("api/products/{productId}")
    suspend fun getProductDetail(
        @Path("productId") productId: Long,
    ): ApiEnvelope<ProductResponse>
}
