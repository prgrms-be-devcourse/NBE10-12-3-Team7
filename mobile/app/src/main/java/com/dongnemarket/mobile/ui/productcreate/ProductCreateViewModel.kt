package com.dongnemarket.mobile.ui.productcreate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dongnemarket.mobile.domain.model.AppError
import com.dongnemarket.mobile.domain.model.NewProduct
import com.dongnemarket.mobile.domain.repository.CategoryRepository
import com.dongnemarket.mobile.domain.repository.MemberRepository
import com.dongnemarket.mobile.domain.repository.ProductRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/**
 * 상품 등록 화면의 상태 보관소.
 *
 * ## 저장소 셋을 쓰는 이유
 *  - [categoryRepository] : 카테고리 선택지. 서버가 `categoryId` 를 필수로 받는다.
 *  - [memberRepository] : **내 동네** — `regionCode` 의 유일한 출처다.
 *    등록은 읍면동(level 3) 코드만 받는데, 앱에 지역 검색 화면이 없으므로
 *    이미 검증된 내 동네를 그대로 쓴다(내 동네 설정도 서버가 같은 level 3 제약을 건다).
 *  - [productRepository] : 실제 등록(이미지 업로드 + 상품 생성).
 *
 * ## 이 화면에 재시도 자동화가 없는 이유
 * 등록은 **쓰기**다. 실패했는지 성공했는데 응답만 못 받았는지 앱은 구분할 수 없고,
 * 자동 재시도는 같은 상품을 두 번 올릴 위험이 있다. 재시도는 사용자가 버튼으로만 한다.
 */
@HiltViewModel
class ProductCreateViewModel @Inject constructor(
    private val productRepository: ProductRepository,
    private val categoryRepository: CategoryRepository,
    private val memberRepository: MemberRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProductCreateUiState())
    val uiState: StateFlow<ProductCreateUiState> = _uiState.asStateFlow()

    init {
        loadForm()
    }

    /**
     * 폼을 채울 두 가지(카테고리·내 동네)를 **동시에** 받아온다.
     *
     * `async` 두 개를 띄우고 나중에 `await` 하는 이유: 순서대로 하면 두 왕복이 더해지지만,
     * 서로 의존하지 않으므로 겹쳐 놓으면 느린 쪽 한 번이면 끝난다.
     *
     * 실패 처리가 둘이 다르다:
     *  - **내 동네 실패는 치명적**이다. `regionCode` 가 없으면 등록 요청 자체를 만들 수 없다 → 화면을 에러로 덮는다.
     *  - **카테고리 실패는 부분적**이다… 로 하고 싶지만 `categoryId` 도 필수라 결국 등록이 불가능하다.
     *    그래서 이쪽도 똑같이 에러로 처리한다. 홈 화면이 카테고리 실패를 무시하는 것과 다른 판단인데,
     *    홈에서 칩은 **필터**(없어도 목록이 보인다)지만 여기서는 **필수 입력**이기 때문이다.
     */
    fun loadForm() {
        _uiState.update { it.copy(isLoadingForm = true, formLoadError = null) }
        viewModelScope.launch {
            val categoriesDeferred = async { categoryRepository.getCategories() }
            val locationsDeferred = async { memberRepository.getMyLocations() }

            val categories = categoriesDeferred.await()
            val locations = locationsDeferred.await()

            val error = categories.exceptionOrNull() ?: locations.exceptionOrNull()
            if (error != null) {
                _uiState.update {
                    it.copy(
                        isLoadingForm = false,
                        formLoadError = (error as? AppError)?.userMessage
                            ?: "등록 화면을 불러오지 못했습니다.",
                    )
                }
                return@launch
            }

            val locationList = locations.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    isLoadingForm = false,
                    categories = categories.getOrDefault(emptyList()),
                    myLocations = locationList,
                    // 동네가 하나면 고를 것이 없고, 둘이면 대표 동네(active)를 기본으로 둔다.
                    // 어느 쪽이든 사용자가 아무것도 안 해도 유효한 값이 이미 들어 있다.
                    selectedRegionCode = it.selectedRegionCode
                        ?: locationList.firstOrNull { location -> location.active }?.region?.code
                        ?: locationList.firstOrNull()?.region?.code,
                )
            }
        }
    }

    // ──────────────────────────── 입력 이벤트 ────────────────────────────

    /**
     * 사진 선택 결과 반영. 선택기가 준 것을 **덧붙이지 않고 교체**한다 —
     * 시스템 선택기가 "이미 고른 것 + 새로 고른 것" 전체를 돌려주기 때문에
     * 덧붙이면 같은 사진이 두 번 들어간다.
     *
     * 상한을 넘겨 오면 앞에서 [MAX_PRODUCT_IMAGES] 장만 취한다.
     * 선택기에 `maxItems` 를 걸어 두지만 구버전 폴백(문서 선택기)에는 그 제한이 없다.
     */
    fun onImagesPicked(uris: List<String>) {
        if (uiState.value.isFormLocked) return
        val kept = uris.take(MAX_PRODUCT_IMAGES)
        _uiState.update {
            it.copy(
                imageUris = kept,
                thumbnailIndex = it.thumbnailIndex.coerceIn(0, (kept.size - 1).coerceAtLeast(0)),
                fieldErrors = it.fieldErrors.copy(images = null),
            )
        }
    }

    /**
     * 사진 한 장 제거.
     *
     * 대표 사진 보정이 이 함수의 핵심이다. 지운 뒤에도 [ProductCreateUiState.thumbnailIndex] 가
     * **범위 안에 있고 같은 사진을 가리켜야** 한다. 그냥 두면
     *  - 마지막 장을 지웠을 때 인덱스가 범위를 벗어나 서버가 400 을 주고,
     *  - 대표보다 앞의 사진을 지웠을 때 대표가 조용히 옆 사진으로 바뀐다.
     */
    fun onRemoveImage(index: Int) {
        if (uiState.value.isFormLocked) return
        _uiState.update { state ->
            if (index !in state.imageUris.indices) return@update state
            val remaining = state.imageUris.toMutableList().apply { removeAt(index) }
            val newThumbnail = when {
                remaining.isEmpty() -> 0
                // 대표 자신을 지웠다 → 같은 자리(밀려 올라온 사진)를 대표로. 끝이면 마지막으로.
                index == state.thumbnailIndex -> index.coerceAtMost(remaining.size - 1)
                // 대표보다 앞을 지웠다 → 대표가 한 칸 당겨졌으니 인덱스도 하나 줄인다.
                index < state.thumbnailIndex -> state.thumbnailIndex - 1
                else -> state.thumbnailIndex
            }
            state.copy(imageUris = remaining, thumbnailIndex = newThumbnail)
        }
    }

    fun onThumbnailSelect(index: Int) {
        if (uiState.value.isFormLocked) return
        _uiState.update {
            if (index in it.imageUris.indices) it.copy(thumbnailIndex = index) else it
        }
    }

    fun onTitleChange(value: String) {
        if (uiState.value.isFormLocked) return
        _uiState.update { it.copy(title = value, fieldErrors = it.fieldErrors.copy(title = null)) }
    }

    fun onDescriptionChange(value: String) {
        if (uiState.value.isFormLocked) return
        _uiState.update { it.copy(description = value) }
    }

    /**
     * 가격 입력. **숫자만 남긴다.**
     *
     * 사용자가 "35,000원"·"3 5000" 처럼 쳐도 받아들이고, 붙여넣기로 들어온 문자도 걸러 낸다.
     * 필터링을 여기서 하면 상태에는 항상 `BigDecimal` 로 바뀔 수 있는 문자열만 남는다.
     * 자리수 상한은 서버에 없지만, 끝없이 길어지면 `BigDecimal` 이 화면을 밀어내므로 앱에서 자른다.
     */
    fun onPriceChange(value: String) {
        if (uiState.value.isFormLocked) return
        val digitsOnly = value.filter { it.isDigit() }.take(MAX_PRICE_DIGITS)
        _uiState.update {
            it.copy(priceInput = digitsOnly, fieldErrors = it.fieldErrors.copy(price = null))
        }
    }

    fun onCategorySelect(categoryId: Long) {
        if (uiState.value.isFormLocked) return
        _uiState.update {
            it.copy(selectedCategoryId = categoryId, fieldErrors = it.fieldErrors.copy(category = null))
        }
    }

    fun onRegionSelect(regionCode: String) {
        if (uiState.value.isFormLocked) return
        _uiState.update {
            it.copy(selectedRegionCode = regionCode, fieldErrors = it.fieldErrors.copy(region = null))
        }
    }

    /** 오류 메시지를 한 번 보여준 뒤 지운다(스낵바가 회전 때마다 다시 뜨지 않게). */
    fun onSubmitErrorShown() {
        _uiState.update { it.copy(submitError = null) }
    }

    // ──────────────────────────── 제출 ────────────────────────────

    /**
     * 등록 실행. **검증 → 업로드 → 생성** 순서이며 검증에서 걸리면 네트워크를 한 번도 건드리지 않는다.
     *
     * 이 순서가 중요하다. 업로드가 먼저 나가고 나중에 제목이 비어서 실패하면
     * 이미 올라간 사진을 지울 방법이 없다(삭제 API 없음). 그래서 **전부 검증한 뒤에야** 첫 요청을 보낸다.
     */
    fun onSubmit() {
        val state = uiState.value
        if (!state.isSubmitEnabled) return

        val errors = validate(state)
        if (errors.hasAny) {
            _uiState.update { it.copy(fieldErrors = errors) }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    fieldErrors = FieldErrors(),
                    submitError = null,
                    phase = CreatePhase.Uploading(done = 0, total = state.imageUris.size),
                )
            }

            val newProduct = NewProduct(
                title = state.title,
                description = state.description,
                // validate() 를 통과했으므로 non-null 이다. 그 사실을 !! 로 재확인하는 대신
                // 기본값을 두면 검증이 무력화되므로, 여기서는 단언이 맞다.
                price = state.parsedPrice!!,
                categoryId = state.selectedCategoryId!!,
                regionCode = state.selectedRegionCode!!,
                imageUris = state.imageUris,
                thumbnailIndex = state.thumbnailIndex,
            )

            productRepository.createProduct(newProduct) { uploaded, total ->
                _uiState.update { current ->
                    // 사진을 다 올렸으면 다음 단계(상품 생성)로 넘어간 것이다.
                    val next = if (uploaded >= total) {
                        CreatePhase.Creating
                    } else {
                        CreatePhase.Uploading(done = uploaded, total = total)
                    }
                    current.copy(phase = next)
                }
            }.fold(
                onSuccess = { productId ->
                    _uiState.update { it.copy(phase = CreatePhase.Done(productId)) }
                },
                onFailure = { cause ->
                    _uiState.update {
                        it.copy(
                            // 실패하면 **반드시 Editing 으로 되돌린다.** 안 그러면 폼이 잠긴 채로 남아
                            // 사용자가 아무것도 고치지도 다시 시도하지도 못한다.
                            phase = CreatePhase.Editing,
                            submitError = (cause as? AppError)?.userMessage
                                ?: "상품을 등록하지 못했습니다.",
                        )
                    }
                },
            )
        }
    }

    /**
     * 서버 규칙을 앱 쪽에 그대로 옮긴 검증.
     * 각 항목 옆의 코드가 이걸 통과 못 했을 때 서버가 주는 실제 응답이다.
     */
    private fun validate(state: ProductCreateUiState): FieldErrors = FieldErrors(
        // 서버: imageUrls @NotEmpty → 400 INVALID_INPUT_VALUE
        images = if (state.imageUris.isEmpty()) "사진을 1장 이상 등록해 주세요." else null,
        // 서버: StringUtils.hasText(title) → 400 INVALID_PRODUCT_TITLE. 공백만 있어도 실패한다.
        title = if (state.title.isBlank()) "제목을 입력해 주세요." else null,
        // 서버: price != null && price.signum() >= 0 → 400 INVALID_PRODUCT_PRICE.
        // 0원은 허용된다(나눔). 입력이 숫자로만 필터링돼 있으므로 음수는 애초에 만들어지지 않지만,
        // 파싱 실패(빈 문자열)는 여기서 잡는다.
        price = when {
            state.priceInput.isBlank() -> "가격을 입력해 주세요."
            state.parsedPrice == null -> "가격은 숫자만 입력할 수 있어요."
            state.parsedPrice!! < BigDecimal.ZERO -> "가격은 0원 이상이어야 해요."
            else -> null
        },
        // 서버: categoryId 로 조회 실패 → 404 CATEGORY_NOT_FOUND
        category = if (state.selectedCategoryId == null) "카테고리를 선택해 주세요." else null,
        // 서버: regionCode 가 없거나 level != 3 → 400 INVALID_INPUT_VALUE
        region = if (state.selectedRegionCode.isNullOrBlank()) "동네를 선택해 주세요." else null,
    )

    private companion object {
        /** 가격 입력 자리수 상한. 서버 제약이 아니라 화면 보호용이다. */
        const val MAX_PRICE_DIGITS = 12
    }
}
