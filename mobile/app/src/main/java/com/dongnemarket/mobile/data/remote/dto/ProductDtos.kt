package com.dongnemarket.mobile.data.remote.dto

import com.dongnemarket.mobile.data.remote.BigDecimalSerializer
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/**
 * 상품 API 응답 DTO 모음. **서버 JSON 키 이름 그대로**이므로 `@SerialName` 이 필요 없다
 * (백엔드에 네이밍 전략 설정이 없어 camelCase 원문이 온다).
 *
 * DTO 는 Data 계층 밖으로 나가지 않는다. Repository 가 `ProductMapper` 로 도메인 모델로 바꿔 준다.
 */

/**
 * `GET /api/products` 의 `data`. 커서 페이징 래퍼.
 *
 * ⚠️ 아이템 필드 이름이 **`items`** 다. 채팅 메시지 래퍼는 같은 구조인데 `messages` 라서
 * 공용 제네릭 페이지 클래스로 묶으면 한쪽이 조용히 null 이 된다 → 도메인별로 따로 선언한다.
 */
@Serializable
data class ProductPageResponse(
    val items: List<ProductSummaryResponse> = emptyList(),
    /** 마지막 페이지에서는 값이 null 로 온다(키 자체는 남는다). */
    val nextCursor: Long? = null,
    /** 서버 getter 가 `isHasNext()` 라서 JSON 키는 `hasNext` 다. */
    val hasNext: Boolean = false,
)

/**
 * 목록·검색·카테고리 응답의 상품 1건. (`ProductSummaryResponse`)
 *
 * 여기에 `createdAt` 이 **없다**. 서버가 상품 시각을 노출하지 않으므로 만들어 붙이지 말 것.
 */
@Serializable
data class ProductSummaryResponse(
    val productId: Long,
    /** 판매자 회원 PK. 요약에는 판매자 닉네임이 없다. */
    val memberId: Long,
    val categoryId: Long,
    val title: String,
    @Serializable(with = BigDecimalSerializer::class)
    val price: BigDecimal,
    /**
     * enum 이 아니라 String 으로 받는다.
     * 서버가 새 상태를 추가해도 파싱이 깨지지 않게 하고, 변환은 `TradeStatus.from()` 에 맡긴다.
     */
    val tradeStatus: String? = null,
    /**
     * 지역 코드(`"11680"`). **서버 필터(`?regionCodes=`)에 넣는 값이 이것이다.**
     *
     * ⚠️ 2026-07 백엔드 지역 모델 개편으로 `region: String` 하나가 이 셋으로 쪼개졌다.
     * 셋 다 nullable + 기본값을 준 이유: 서버가 non-null 로 주지만, 키가 빠져도
     * 목록 전체가 파싱 실패로 죽지 않게 하기 위한 방어다(빈 문자열로 떨어진다).
     */
    val regionCode: String? = null,
    /** 짧은 표시 이름(`"강남구"`). 카드에 쓴다. */
    val regionName: String? = null,
    /** 전체 표시 이름(`"서울특별시 강남구"`). */
    val regionFullName: String? = null,
    val viewCount: Long = 0,
    val favoriteCount: Int = 0,
    /** 상대 경로(`/api/products/images/...`)이거나 null. 절대 URL 변환은 매퍼가 한다. */
    val thumbnailUrl: String? = null,
    /** 서버 getter 가 `isHidden()` 이라서 JSON 키는 `hidden` 이다. */
    val hidden: Boolean = false,
)

/**
 * `GET /api/products/{productId}` 의 `data`. (`ProductResponse`)
 *
 * 요약과 다른 점: `sellerNickname`·`description`·`imageUrls` 가 추가된다.
 * 없는 것: 작성 시각, **'내가 찜했는지'**(favoriteCount 는 개수일 뿐이다).
 */
@Serializable
data class ProductResponse(
    val productId: Long,
    val memberId: Long,
    val sellerNickname: String? = null,
    val categoryId: Long,
    val title: String,
    val description: String? = null,
    @Serializable(with = BigDecimalSerializer::class)
    val price: BigDecimal,
    val tradeStatus: String? = null,
    /** 지역 3분할 — 자세한 사유는 [ProductSummaryResponse.regionCode] 참고. */
    val regionCode: String? = null,
    val regionName: String? = null,
    val regionFullName: String? = null,
    /** 이 조회로 +1 된 값이 그대로 담겨 온다. */
    val viewCount: Long = 0,
    val favoriteCount: Int = 0,
    val thumbnailUrl: String? = null,
    /** sortOrder ASC, 최대 5개. 이미지 없는 상품은 `[]`(빈 배열)로 온다. */
    val imageUrls: List<String> = emptyList(),
    val hidden: Boolean = false,
)
