package com.dongnemarket.mobile.domain.model

import java.math.BigDecimal

/**
 * 아직 서버에 올라가지 않은 "등록할 상품". 등록 요청 하나를 값 하나로 묶는다.
 *
 * [Product]·[ProductDetail] 과 따로 두는 이유: 저 둘은 **서버가 준 것**이라
 * `productId`·`viewCount`·`favoriteCount` 처럼 등록 시점에는 존재할 수 없는 값을 갖는다.
 * 등록 입력을 그 모델에 억지로 태우면 "아직 없는 필드"를 0이나 -1로 채워야 하고,
 * 그 가짜 값은 언젠가 화면에 새어 나온다.
 *
 * ### 왜 [imageUris] 가 `Uri` 가 아니라 `String` 인가
 * 사진 선택기는 `content://media/...` 형태의 **`android.net.Uri`** 를 준다.
 * 하지만 이 모델은 Domain 계층이고, Domain 은 Android 프레임워크를 알면 안 된다
 * (알게 되면 JVM 단위 테스트에서 이 파일을 건드리는 순간 `Uri.parse` 가 null 을 뱉는다 —
 * android.jar 스텁 메소드는 전부 예외를 던지거나 null 을 준다).
 *
 * 그래서 UI 가 `uri.toString()` 으로 문자열화해 넘기고,
 * Data 계층(`ImageCompressor`)이 `Uri.parse` 로 되돌려 실제 바이트를 읽는다.
 * 문자열은 어느 계층에서나 안전하게 지나다닌다.
 *
 * @param title 공백만 있으면 서버가 400 `INVALID_PRODUCT_TITLE`.
 * @param description **빈 문자열은 되지만 null 은 안 된다.** 서버가 검증 없이 역참조해 500 이 난다.
 * @param price 0 이상. 음수면 400 `INVALID_PRODUCT_PRICE`. 0원(나눔)은 허용된다.
 * @param regionCode ⚠️ **읍면동(level 3) 코드만** 받는다. "내 동네"가 정확히 이 단위다.
 * @param imageUris 1~5개. 0개면 등록 자체가 불가능하다(서버 `@NotEmpty`).
 * @param thumbnailIndex 대표 이미지 위치. `0 ≤ index < imageUris.size`.
 */
data class NewProduct(
    val title: String,
    val description: String,
    val price: BigDecimal,
    val categoryId: Long,
    val regionCode: String,
    val imageUris: List<String>,
    val thumbnailIndex: Int,
)
