package com.dongnemarket.mobile.domain.repository

import com.dongnemarket.mobile.domain.model.Product
import com.dongnemarket.mobile.domain.model.ProductDetail
import com.dongnemarket.mobile.domain.model.ProductPage

/**
 * 상품 조회 창구. **인터페이스를 Domain 이 소유**하고 구현은 Data 계층에 둔다(의존성 역전).
 * Spring 에서 서비스가 `ProductRepository` 인터페이스만 알고 JPA 구현체를 모르는 것과 같은 구도다.
 *
 * 공통 규칙
 * - 예외를 던지지 않는다. 실패는 항상 `Result.failure(AppError)` 로 온다
 *   → ViewModel 은 `onFailure { it.userMessage }` 만 보면 된다.
 * - 반환 타입은 DTO 가 아니라 도메인 모델이다. 이미지 URL 은 이미 절대 URL 로 변환되어 있다.
 */
interface ProductRepository {

    /**
     * 홈 상품 목록 — **커서 페이징**(무한스크롤).
     *
     * @param regionCodes 지역 **코드** 목록(`"11680"`). 2026-07 개편 전의 이름 문자열이 아니다.
     *   서버 상한이 **2개**이므로 3개 이상을 넘기면 구현체가 앞의 2개만 보낸다(넘기면 400).
     *   null/빈 리스트면 전국 조회.
     * @param cursor 마지막으로 받은 `productId`. **첫 페이지는 null.**
     * @param size 한 페이지 개수. 서버 기본 30, 100 초과는 서버가 조용히 100 으로 깎는다.
     *
     * 이 목록은 서버가 삭제·숨김·거래완료·비활성 판매자 상품을 이미 걸러 주므로
     * 결과의 `tradeStatus` 는 `ON_SALE`/`RESERVED` 뿐이고 `hidden` 은 항상 false 다.
     * 정렬은 `id DESC`(최신 등록순) 고정 — 정렬 옵션 파라미터는 존재하지 않는다.
     */
    suspend fun getProducts(
        regionCodes: List<String>? = null,
        cursor: Long? = null,
        size: Int = 30,
    ): Result<ProductPage>

    /**
     * 검색 + 카테고리 필터. **페이징이 없고 조건에 맞는 전량이 한 번에 온다.**
     *
     * 그래서 반환 타입이 [ProductPage] 가 아니라 `List<Product>` 다(비대칭이지만 서버 사실이다).
     * 무한스크롤을 붙일 수 없으므로 화면에서 표시 개수를 잘라 렌더할 것.
     *
     * 카테고리 칩도 이 함수로 처리한다. `GET /api/categories/{id}/products` 는 지역 필터를
     * 받지 못해 "내 동네 + 카테고리" 조합이 불가능해서 쓰지 않는다.
     *
     * @param keyword 제목·설명 부분일치(대소문자 무시). 공백/빈 문자열은 구현체가 빼고 보낸다.
     * @param categoryId 완전일치. 존재하지 않는 id 는 에러가 아니라 **결과 0건**이다.
     * @param regionCodes [getProducts] 와 같은 규칙(지역 **코드**, 최대 2개).
     */
    suspend fun searchProducts(
        keyword: String? = null,
        categoryId: Long? = null,
        regionCodes: List<String>? = null,
    ): Result<List<Product>>

    /**
     * 상품 상세.
     *
     * ⚠️ **이 조회는 서버에서 조회수를 +1 하는 쓰기 동작이다.**
     * 화면 회전·재구성·자동 재시도마다 조회수가 올라가므로
     * ViewModel `init`(또는 `SavedStateHandle` 가드)에서 **1회만** 호출하고,
     * 실패 시 자동 재시도를 걸지 마라(사용자가 누르는 재시도 버튼만 허용).
     *
     * 404 가 정상적으로 발생할 수 있는 경우: 삭제된 상품, **거래완료된 상품**,
     * 판매자가 탈퇴/정지된 상품. 즉 목록에서 방금 본 상품도 상세에서 404 가 날 수 있다
     * → "삭제된 상품입니다" 정도로 부드럽게 안내한다. 숨김 상품은 403 이다.
     */
    suspend fun getProductDetail(productId: Long): Result<ProductDetail>
}
