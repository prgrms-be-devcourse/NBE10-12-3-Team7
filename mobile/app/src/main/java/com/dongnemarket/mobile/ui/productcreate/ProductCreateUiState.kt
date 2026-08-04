package com.dongnemarket.mobile.ui.productcreate

import com.dongnemarket.mobile.domain.model.Category
import com.dongnemarket.mobile.domain.model.MemberLocation
import java.math.BigDecimal

/** 등록 화면이 최대 몇 장까지 받는지. 서버 상한(`@Size(max = 5)`)과 같아야 한다. */
const val MAX_PRODUCT_IMAGES = 5

/**
 * 등록이 지금 어느 단계인지. 화면은 이 값 하나로 버튼·진행률·이동을 모두 정한다.
 *
 * `Boolean` 여러 개(`isUploading`, `isCreating`, `isDone`) 대신 sealed 로 둔 이유:
 * 불리언 셋이면 "업로드 중이면서 완료"처럼 **있을 수 없는 조합**이 타입상 표현 가능해지고,
 * 언젠가 그 조합이 실제로 만들어진다. 단계는 본래 배타적이므로 배타적인 타입으로 적는다.
 */
sealed interface CreatePhase {
    /** 입력 중. 이 상태에서만 폼을 고칠 수 있다. */
    data object Editing : CreatePhase

    /** 사진 업로드 중. `done/total` 로 "2/3장" 을 그린다. */
    data class Uploading(val done: Int, val total: Int) : CreatePhase

    /** 사진은 다 올렸고 상품 본문을 등록하는 중. */
    data object Creating : CreatePhase

    /** 등록 성공. 화면이 이 값을 보고 상세로 이동한다. */
    data class Done(val productId: Long) : CreatePhase
}

/**
 * 필드별 검증 메시지. null 이면 문제 없음.
 *
 * 왜 서버에 맡기지 않고 앱이 먼저 보는가: 서버는 제목·가격을 뺀 나머지 위반을
 * **전부 `INVALID_INPUT_VALUE` 하나**로 뭉쳐 준다. "무엇이 잘못됐는지" 정보가 응답에 없으니
 * 앱이 알려 주지 않으면 사용자는 어느 칸을 고쳐야 하는지 영영 모른다.
 *
 * 더 큰 이유는 **순서**다. 등록은 사진 업로드가 먼저다.
 * 검증을 서버에 맡기면 사진 5장을 다 올린 뒤에야 "제목이 비었습니다"를 듣게 되고,
 * 그때 이미 올라간 파일은 되돌릴 방법이 없다(고아 파일).
 */
data class FieldErrors(
    val images: String? = null,
    val title: String? = null,
    val price: String? = null,
    val category: String? = null,
    val region: String? = null,
) {
    val hasAny: Boolean
        get() = images != null || title != null || price != null || category != null || region != null
}

/**
 * 상품 등록 화면의 상태 전부.
 *
 * 폼 화면이라 상태가 `Loading/Success/Error` 로 갈라지지 않고 **하나의 data class** 다.
 * 홈([com.dongnemarket.mobile.ui.home.HomeUiState])처럼 sealed 로 쪼개면
 * 로딩이 끝날 때마다 사용자가 입력하던 제목·사진을 어느 가지로 옮길지 매번 고민하게 된다.
 * 입력값은 로딩·에러와 **동시에** 존재해야 하므로 한 덩어리로 둔다.
 *
 * @param isLoadingForm 카테고리·내 동네를 받아오는 중. 폼 자체를 가린다.
 * @param formLoadError 그 조회가 실패함. 입력할 수 없으므로 재시도 화면을 보여준다.
 * @param myLocations 내 동네(최대 2건). **비어 있으면 등록 자체가 불가능**하다 —
 *   서버가 `regionCode` 를 필수로 받고, 그 코드의 출처가 여기뿐이기 때문이다.
 * @param imageUris 선택한 사진의 `content://` URI 문자열. 순서가 곧 화면 순서다.
 * @param thumbnailIndex 대표 사진 위치. 사진을 지우면 [com.dongnemarket.mobile.ui.productcreate.ProductCreateViewModel]
 *   이 범위를 다시 맞춰 준다(범위를 벗어난 채 보내면 서버가 400).
 * @param priceInput **숫자 문자열**로 들고 있는다. `BigDecimal` 로 즉시 바꾸지 않는 이유는
 *   입력 도중의 빈 문자열("")을 표현할 수 없고, 0 으로 대체하면 사용자가 지운 칸이 0원으로 보이기 때문이다.
 */
data class ProductCreateUiState(
    val isLoadingForm: Boolean = true,
    val formLoadError: String? = null,

    val categories: List<Category> = emptyList(),
    val myLocations: List<MemberLocation> = emptyList(),

    val imageUris: List<String> = emptyList(),
    val thumbnailIndex: Int = 0,
    val title: String = "",
    val description: String = "",
    val priceInput: String = "",
    val selectedCategoryId: Long? = null,
    val selectedRegionCode: String? = null,

    val phase: CreatePhase = CreatePhase.Editing,
    val fieldErrors: FieldErrors = FieldErrors(),
    /** 서버가 거부했거나 통신이 실패함. 한 번 보여주고 지운다. */
    val submitError: String? = null,
) {
    /** 사진을 더 고를 수 있는지. 상한에 닿으면 선택 버튼을 감춘다. */
    val canAddImage: Boolean
        get() = imageUris.size < MAX_PRODUCT_IMAGES

    /** 동네를 설정하지 않은 회원. 폼 대신 안내를 띄운다. */
    val hasNoRegion: Boolean
        get() = !isLoadingForm && formLoadError == null && myLocations.isEmpty()

    /** 제출 버튼을 누를 수 있는지. **입력 완성도가 아니라 "작업 중이 아님"만 본다.** */
    val isSubmitEnabled: Boolean
        get() = phase is CreatePhase.Editing && !hasNoRegion

    /** 진행 중이면 폼 전체를 잠근다(입력이 바뀌면 이미 보낸 것과 어긋난다). */
    val isFormLocked: Boolean
        get() = phase !is CreatePhase.Editing

    /**
     * 가격 문자열 → [BigDecimal]. 형식이 아니면 null.
     *
     * 화면 표시와 제출이 **같은 해석**을 쓰도록 상태에 둔다.
     * ViewModel 과 화면이 각자 파싱하면 "화면엔 35,000원인데 34,000원이 등록되는" 종류의
     * 어긋남이 생길 수 있다.
     */
    val parsedPrice: BigDecimal?
        get() = priceInput.takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }
}
