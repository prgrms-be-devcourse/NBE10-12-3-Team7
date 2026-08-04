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

/**
 * `POST /api/products/images` 의 `data`.
 *
 * 보낸 파일 순서대로 저장 경로가 돌아온다(`/api/products/images/{uuid}.jpg`).
 * **여기서 받은 문자열을 가공하지 말고 그대로** [ProductCreateRequestDto.imageUrls] 에 실어야 한다 —
 * 서버가 이 값을 그대로 DB 에 저장하기 때문이다(절대 URL 로 바꿔 보내면 그 URL 이 저장된다).
 */
@Serializable
data class ProductImageUploadResponse(
    val imageUrls: List<String> = emptyList(),
)

/**
 * `POST /api/products` 요청 본문.
 *
 * ### 모든 필드가 non-null 인 이유
 * 서버 DTO(`ProductCreateRequest`)는 전부 nullable 이지만, **앱이 null 을 보내면 안 된다.**
 * `ProductService` 는 `title`·`price`·`imageUrls` 만 검증한 뒤
 * `Product.create(..., request.description!!, ...)` 로 **description 을 검증 없이 역참조**한다.
 * 즉 description 이 없으면 400 이 아니라 **NPE → 500** 이다.
 *
 * 게다가 앱의 Json 설정은 `explicitNulls = false` 라 **null 필드는 키째 사라진다** →
 * "설명을 안 적었을 뿐인데 서버가 500" 이라는 경로를 실제로 밟게 된다.
 * 그래서 설명 미입력은 null 이 아니라 **빈 문자열**로 보낸다.
 *
 * @param price `BigDecimal` 을 문자열이 아닌 **JSON 숫자**로 실어야 한다 → [BigDecimalSerializer].
 * @param regionCode 지역 코드. ⚠️ 서버가 `level == 3`(읍면동)만 받는다.
 *   시/도(1)·시군구(2) 코드를 보내면 400 `INVALID_INPUT_VALUE` 다.
 * @param imageUrls 업로드 응답에서 받은 경로들. 1~5개, 빈 문자열 불가.
 * @param thumbnailIndex `0 ≤ index < imageUrls.size`. 벗어나면 400.
 */
@Serializable
data class ProductCreateRequestDto(
    val categoryId: Long,
    val title: String,
    val description: String,
    @Serializable(with = BigDecimalSerializer::class)
    val price: BigDecimal,
    val regionCode: String,
    val imageUrls: List<String>,
    val thumbnailIndex: Int,
)
