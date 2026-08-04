package com.dongnemarket.mobile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dongnemarket.mobile.domain.model.AppError
import com.dongnemarket.mobile.domain.model.Category
import com.dongnemarket.mobile.domain.model.MemberLocation
import com.dongnemarket.mobile.domain.model.Product
import com.dongnemarket.mobile.domain.repository.CategoryRepository
import com.dongnemarket.mobile.domain.repository.MemberRepository
import com.dongnemarket.mobile.domain.repository.ProductRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 홈 첫 페이지·다음 페이지 크기. 서버 기본값과 같은 30(초과분은 서버가 100으로 클램프). */
private const val PAGE_SIZE = 30

/**
 * 검색·카테고리 결과 표시 상한.
 *
 * 그 두 조회는 `GET /api/products/search` 로 **페이징 없이 전량**이 온다(계약 §2-4).
 * 상품이 수천 건이면 응답 하나가 그대로 리스트가 되어 메모리·렌더링이 터지므로 앱에서 잘라 낸다.
 * 서버에 페이징이 생기면 이 상수를 지우고 커서 방식으로 바꾸면 된다.
 */
private const val MAX_FILTERED_ITEMS = 200

/**
 * 홈(상품목록) 화면의 상태 보유자.
 *
 * Spring 의 `@Service` 와 비슷한 자리다 — 화면(Compose)은 그리기만 하고,
 * "무엇을 언제 불러오는가" 는 전부 여기서 결정한다.
 *
 * 이 화면이 유독 복잡한 이유는 **목록의 출처가 3가지이고 페이징 특성이 서로 다르기** 때문이다.
 *
 * | 상태 | 호출 | 페이징 |
 * |---|---|---|
 * | 필터 없음 | `getProducts(regions, cursor, size)` | 커서 페이징 O → 무한스크롤 |
 * | 검색어 있음 | `searchProducts(keyword, categoryId, regions)` | X (전량 반환) |
 * | 카테고리 칩 선택 | `searchProducts(categoryId, regions)` | X (전량 반환) |
 *
 * 그래서 필터가 걸린 동안에는 [onLoadMore] 가 아무 일도 하지 않는다(다음 페이지가 존재하지 않는다).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val productRepository: ProductRepository,
    private val categoryRepository: CategoryRepository,
    private val memberRepository: MemberRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * 화면 상태의 원본. [_uiState] 는 이걸 투영한 결과다.
     *
     * 왜 따로 두는가: [HomeUiState.Error] 에는 message 밖에 없어서 에러가 한 번 나면
     * 이미 받아 둔 카테고리·동네·선택된 필터가 사라진다. 그러면 재시도 후 사용자가 고른
     * 카테고리가 "전체" 로 되돌아가 버린다. 원본을 여기 남겨 두고 [render] 로만 내보낸다.
     *
     * 이 변수는 `viewModelScope`(메인 디스패처)에서만 읽고 쓴다 → 단일 스레드 확정이라 락이 필요 없다.
     */
    private var snapshot = Snapshot()

    /** 첫 로드/전체 재시도 작업. 필터 변경 작업과 구분해 각자 취소할 수 있게 나눠 둔다. */
    private var bootstrapJob: Job? = null

    /** 목록 조회 작업(필터 변경·다음 페이지). 새 요청이 들어오면 이전 요청을 취소한다. */
    private var listJob: Job? = null

    /**
     * '목록을 채우는 요청'의 세대 번호. 새 조회가 시작될 때마다 올라간다.
     *
     * [bootstrapJob] 은 카테고리·동네도 함께 받아 오므로 [loadFirstPage] 에서 통째로 취소할 수 없다.
     * 그런데 취소하지 않고 두면, 첫 진입 로드가 끝나기 전에 검색·카테고리가 들어왔을 때
     * 나중에 도착한 bootstrap 의 상품 결과가 **필터 결과를 전국 기본 목록으로 덮어쓴다**
     * (게다가 hasNext 가 true 로 되살아나 무한스크롤 가드까지 깨진다).
     * 그래서 상품 결과를 반영하기 직전에 "내가 아직 최신 요청인가"를 이 값으로 확인한다.
     * 사용자가 마지막으로 요청한 조회의 결과만 화면에 남아야 한다.
     */
    private var listGeneration = 0

    init {
        load()
    }

    // ──────────────────────────── 이벤트(화면이 부르는 것) ────────────────────────────

    /**
     * 검색 확정. 검색바가 **IME 검색 버튼을 누른 순간에만** 부른다(타이핑마다 부르지 않는다).
     *
     * 빈 문자열을 주면 검색이 해제되어 기본 목록(커서 페이징)으로 돌아간다 — 검색바의 X 버튼이 이 경로다.
     */
    fun onSearch(keyword: String) {
        snapshot = snapshot.copy(keyword = keyword.trim())
        loadFirstPage()
    }

    /**
     * 카테고리 칩 선택. [categoryId] 가 null 이면 "전체" 칩이다.
     *
     * 같은 칩을 다시 누르면 아무 일도 하지 않는다 — 서버 왕복 한 번을 아끼고,
     * 스크롤 위치가 초기화되는 것도 막는다.
     */
    fun onCategorySelect(categoryId: Long?) {
        if (categoryId == snapshot.selectedCategoryId) return
        snapshot = snapshot.copy(selectedCategoryId = categoryId)
        loadFirstPage()
    }

    /**
     * 무한스크롤 — 그리드가 끝에 가까워졌을 때 화면이 부른다.
     *
     * 화면은 조건을 따지지 않고 그냥 불러도 된다. 실제 요청을 걸어도 되는지는 아래 3중 가드가 판단한다:
     *  1. `isAppending` — **없으면 스크롤 한 번에 같은 페이지를 여러 번 부른다**(가장 흔한 버그).
     *  2. `hasNext` — `nextCursor == null` 로 판단하지 않는다(서버가 마지막 페이지에도 키를 남긴다).
     *  3. 필터 모드 제외 — 검색·카테고리 결과에는 다음 페이지가 아예 없다.
     */
    fun onLoadMore() {
        val current = snapshot
        if (current.isAppending || !current.hasNext || current.isFiltering) return
        val cursor = current.nextCursor ?: return

        snapshot = current.copy(isAppending = true)
        render()

        listJob = viewModelScope.launch {
            productRepository.getProducts(
                regionCodes = current.filterRegions,
                cursor = cursor,
                size = PAGE_SIZE,
            )
                .onSuccess { page ->
                    snapshot = snapshot.copy(
                        // distinctBy: 커서 페이징 도중 상품이 등록/삭제되면 경계에서 같은 상품이
                        // 두 페이지에 걸쳐 올 수 있다. LazyGrid 의 key 가 중복되면 즉시 크래시한다.
                        products = (snapshot.products + page.items).distinctBy { it.productId },
                        nextCursor = page.nextCursor,
                        hasNext = page.hasNext,
                        isAppending = false,
                    )
                    render()
                }
                .onFailure {
                    // 다음 페이지 실패로 이미 보고 있던 목록을 에러 화면으로 덮지 않는다.
                    // hasNext 를 그대로 두므로 사용자가 다시 스크롤하면 재시도된다.
                    snapshot = snapshot.copy(isAppending = false)
                    render()
                }
        }
    }

    /**
     * 에러 화면의 "다시 시도".
     *
     * 첫 로드가 실패했다면 동네·카테고리까지 전부 다시, 그 뒤에 실패했다면 목록만 다시 받는다
     * (이미 있는 카테고리를 또 받을 이유가 없다).
     */
    fun onRetry() {
        if (snapshot.bootstrapped) loadFirstPage() else load()
    }

    /**
     * 홈이 다시 화면 앞으로 나왔다(상세·등록에서 돌아옴). **목록만** 조용히 다시 받는다.
     *
     * ### 이게 없으면 생기는 일
     * 상품을 등록하고 홈으로 돌아오면 **방금 올린 물건이 목록에 없다.**
     * ViewModel 은 `init` 에서 한 번 조회하고, 뒤로가기로 돌아와도 그 결과를 그대로 들고 있기 때문이다.
     * 사용자에게는 "등록이 실패한 것"으로 보인다 — 실제로는 서버에 잘 저장돼 있는데도.
     * (2026-08-04 에뮬 실기 검수에서 발견. 단위·계기 테스트는 화면 **복귀**를 재현하지 않아 잡지 못했다.)
     *
     * 등록만의 문제가 아니다. 상세에서 찜을 누르거나 조회수가 오른 것도 반영되지 않는다.
     *
     * ### 왜 [load] 가 아니라 [loadFirstPage] 인가
     * [load] 는 화면 전체를 `Loading` 으로 되돌려 카테고리 칩·검색어까지 잠깐 사라지게 한다.
     * 돌아올 때마다 화면이 깜빡이면 오히려 고장처럼 보인다. 카테고리·동네는 자주 바뀌지 않으므로
     * 목록만 새로 받는 편이 맞다.
     *
     * ### 첫 진입에서 두 번 부르지 않기
     * `bootstrapped` 가 false 면 [init] 의 [load] 가 아직 진행 중이라는 뜻이다.
     * 그때 겹쳐 부르면 같은 목록을 두 번 받는다(세대 번호 덕에 결과가 꼬이지는 않지만 낭비다).
     */
    fun onScreenResumed() {
        if (!snapshot.bootstrapped) return
        loadFirstPage()
    }

    // ──────────────────────────── 내부 로딩 ────────────────────────────

    /**
     * 첫 진입 로드: **내 동네 → (카테고리 ∥ 상품)**.
     *
     * 동네를 먼저 받아야 하는 이유는 순서 의존이다 — 상품 목록의 `regions` 필터에 동네 '이름'
     * 문자열이 들어가므로 동네를 모르면 상품을 부를 수 없다. 반면 카테고리와 상품은 서로
     * 독립이라 [async] 로 동시에 띄운다(순차로 하면 첫 화면이 그만큼 늦어진다).
     *
     * 실패 정책이 셋 다 다르다:
     *  - 동네 실패 → 헤더 동네 줄 생략 + **전국 조회**로 계속 진행(로그인 안 한 상태도 여기로 온다)
     *  - 카테고리 실패 → 칩 영역만 비움
     *  - **상품 실패 → 화면 전체 Error** (본문이 없으면 홈이 성립하지 않는다)
     */
    private fun load() {
        listJob?.cancel()
        bootstrapJob?.cancel()
        _uiState.value = HomeUiState.Loading

        val generation = ++listGeneration
        bootstrapJob = viewModelScope.launch {
            // 1) 동네 (선행)
            val locations = memberRepository.getMyLocations().getOrNull().orEmpty()

            // 2) 카테고리·상품 (병렬)
            val categoriesDeferred = async { categoryRepository.getCategories() }
            val filterRegions = locations.toFilterRegionCodes()
            val productsDeferred = async {
                productRepository.getProducts(regionCodes = filterRegions, cursor = null, size = PAGE_SIZE)
            }

            val categories = categoriesDeferred.await().getOrElse { emptyList() }
            val productsResult = productsDeferred.await()

            snapshot = snapshot.copy(
                categories = categories,
                region = locations.activeRegionName(),
                filterRegions = filterRegions,
                bootstrapped = true,
            )

            // 카테고리·동네는 필터와 무관하므로 항상 반영한다(위 copy). 하지만 **상품 목록은**
            // 그 사이 사용자가 검색·카테고리를 눌렀다면 이미 낡은 결과다 → 덮어쓰지 않고 버린다.
            if (generation != listGeneration) {
                render() // 카테고리·동네만 화면에 반영하고 목록은 최신 요청에 맡긴다.
                return@launch
            }

            productsResult
                .onSuccess { page ->
                    snapshot = snapshot.copy(
                        products = page.items,
                        nextCursor = page.nextCursor,
                        hasNext = page.hasNext,
                        isAppending = false,
                    )
                    render()
                }
                .onFailure { _uiState.value = HomeUiState.Error(it.toUserMessage()) }
        }
    }

    /**
     * 현재 필터로 목록의 **첫 페이지를 다시** 받는다(검색 확정·칩 변경·부분 재시도).
     *
     * 화면 전체를 [HomeUiState.Loading] 으로 되돌리지 않는다 — 그러면 칩과 검색어까지 사라져
     * 사용자가 방금 누른 칩이 보이지 않는다. 대신 목록만 비우고 `isAppending = true` 로 두어
     * 그리드 자리에만 스피너가 돌게 한다.
     */
    private fun loadFirstPage() {
        listJob?.cancel()
        // 아직 살아 있는 bootstrapJob 의 상품 결과가 나중에 도착해 이 조회를 덮어쓰지 않도록
        // 세대를 올린다(bootstrapJob 자체는 카테고리·동네도 받아 오므로 취소하지 않는다).
        // 이 조회 자신의 결과는 listJob 취소로 보호되므로 세대를 붙들고 있을 필요는 없다.
        listGeneration++

        val current = snapshot.copy(
            products = emptyList(),
            nextCursor = null,
            hasNext = false,
            isAppending = true,
        )
        snapshot = current
        render()

        listJob = viewModelScope.launch {
            if (current.isFiltering) {
                // 검색·카테고리: 페이징이 없으므로 전량을 받아 앞에서부터 잘라 쓴다.
                productRepository.searchProducts(
                    keyword = current.keyword.takeIf { it.isNotBlank() },
                    categoryId = current.selectedCategoryId,
                    regionCodes = current.filterRegions,
                )
                    .onSuccess { items ->
                        snapshot = snapshot.copy(
                            products = items.take(MAX_FILTERED_ITEMS),
                            nextCursor = null,
                            hasNext = false, // 다음 페이지라는 개념이 없다
                            isAppending = false,
                        )
                        render()
                    }
                    .onFailure { _uiState.value = HomeUiState.Error(it.toUserMessage()) }
            } else {
                productRepository.getProducts(
                    regionCodes = current.filterRegions,
                    cursor = null,
                    size = PAGE_SIZE,
                )
                    .onSuccess { page ->
                        snapshot = snapshot.copy(
                            products = page.items,
                            nextCursor = page.nextCursor,
                            hasNext = page.hasNext,
                            isAppending = false,
                        )
                        render()
                    }
                    .onFailure { _uiState.value = HomeUiState.Error(it.toUserMessage()) }
            }
        }
    }

    /** [snapshot] 을 화면이 읽는 [HomeUiState.Success] 로 내보낸다. */
    private fun render() {
        _uiState.value = HomeUiState.Success(
            products = snapshot.products,
            categories = snapshot.categories,
            region = snapshot.region,
            selectedCategoryId = snapshot.selectedCategoryId,
            keyword = snapshot.keyword,
            isAppending = snapshot.isAppending,
            hasNext = snapshot.hasNext,
        )
    }

    /**
     * 화면이 들고 있는 값 + 화면에 노출하지 않는 페이징 내부값.
     *
     * `nextCursor`·`filterRegions`·`bootstrapped` 는 UI 가 알 필요가 없어서 [HomeUiState] 에 넣지 않았다.
     */
    private data class Snapshot(
        val products: List<Product> = emptyList(),
        val categories: List<Category> = emptyList(),
        val region: String? = null,
        /** 상품 조회에 실어 보낼 동네 '이름' 목록(최대 2개). null 이면 전국 조회. */
        val filterRegions: List<String>? = null,
        val selectedCategoryId: Long? = null,
        val keyword: String = "",
        val isAppending: Boolean = false,
        val hasNext: Boolean = false,
        /** 다음 커서(= 마지막으로 받은 productId). 마지막 페이지면 null. */
        val nextCursor: Long? = null,
        /** 동네·카테고리를 한 번이라도 받아 봤는가(재시도 범위 결정용). */
        val bootstrapped: Boolean = false,
    ) {
        val isFiltering: Boolean
            get() = keyword.isNotBlank() || selectedCategoryId != null
    }
}

/**
 * 헤더에 찍을 대표 동네 이름. 동네가 없으면 null.
 *
 * `active` 를 먼저 보고, 없으면 `sortOrder` 최솟값으로 폴백한다(서버 규칙상 둘은 같은 항목이지만
 * 플래그만 어긋난 응답에서 동네가 있는 사용자의 헤더가 비는 것을 막는다).
 */
private fun List<MemberLocation>.activeRegionName(): String? =
    (firstOrNull { it.active } ?: minByOrNull { it.sortOrder })?.region?.display

/**
 * 내 동네 → 상품 필터용 지역 '이름' 목록.
 *
 * 두 가지가 서버 제약이다: 넘기는 값은 regionId 가 아니라 **이름 원문**(`"서울 강남구"`)이고,
 * **최대 2개**다(3개 이상이면 400). 동네가 없으면 null 을 돌려 파라미터 자체를 생략시킨다(= 전국).
 */
private fun List<MemberLocation>.toFilterRegionCodes(): List<String>? =
    sortedBy { it.sortOrder }
        .map { it.region.code }
        .filter { it.isNotBlank() }
        .take(2)
        .takeIf { it.isNotEmpty() }

/**
 * 실패 → 사용자에게 보여 줄 문장.
 *
 * 백엔드 `error` 코드(`PRODUCT_NOT_FOUND` 같은 상수명)는 절대 노출하지 않는다.
 * 500 도 "서버 장애" 로 단정하지 않는다 — cursor/size 에 이상한 값을 보내면 서버가 400 대신
 * 500 을 주기 때문에(계약 §7-4) 우리 버그를 서버 탓으로 오인하게 된다.
 */
private fun Throwable.toUserMessage(): String =
    (this as? AppError)?.userMessage ?: "요청을 처리할 수 없습니다."
