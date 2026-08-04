package com.dongnemarket.mobile.ui.home

import app.cash.turbine.test
import com.dongnemarket.mobile.domain.model.AppError
import com.dongnemarket.mobile.domain.model.Category
import com.dongnemarket.mobile.domain.model.MemberLocation
import com.dongnemarket.mobile.domain.model.Product
import com.dongnemarket.mobile.domain.model.ProductPage
import com.dongnemarket.mobile.domain.model.TradeStatus
import com.dongnemarket.mobile.domain.model.RegionRef
import com.dongnemarket.mobile.domain.repository.CategoryRepository
import com.dongnemarket.mobile.domain.repository.MemberRepository
import com.dongnemarket.mobile.domain.repository.ProductRepository
// eq() · isNull() · any() 는 MockKMatcherScope 의 멤버라 import 하지 않는다(coEvery/coVerify 블록 안에서만 쓴다).
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.math.BigDecimal

/**
 * 홈 화면 상태 보유자([HomeViewModel])의 명세.
 *
 * 이 화면이 검증할 가치가 있는 이유는 **목록의 출처가 3가지이고 페이징 특성이 서로 다르기** 때문이다.
 *
 * | 사용자가 한 일 | 호출되는 API | 다음 페이지 |
 * |---|---|---|
 * | 홈 진입 | `getProducts(regions, cursor, size)` | 있음(커서 페이징) |
 * | 검색어 확정 | `searchProducts(keyword, ...)` | 없음(전량 반환) |
 * | 카테고리 칩 선택 | `searchProducts(categoryId, ...)` | 없음(전량 반환) |
 *
 * 검증은 전부 **공개 API(`uiState` + 이벤트 함수)** 로만 한다. 내부 Snapshot 은 들여다보지 않는다.
 */
class HomeViewModelTest {

    // 타입을 TestWatcher 로 적는 이유: 구현 클래스가 이 파일 전용 private 이라
    // 공개 프로퍼티가 private 타입을 노출하면 컴파일되지 않는다.
    @get:Rule
    val mainDispatcherRule: TestWatcher = MainDispatcherRule()

    private val productRepository = mockk<ProductRepository>()
    private val categoryRepository = mockk<CategoryRepository>()
    private val memberRepository = mockk<MemberRepository>()

    private fun 홈화면을_연다() =
        HomeViewModel(productRepository, categoryRepository, memberRepository)

    // ─────────────────────────── 1. 첫 진입 ───────────────────────────

    @Test
    fun `홈에 들어가면 먼저 Loading 을 보여 주고 상품이 도착한 뒤에 Success 로 바뀐다`() = runTest {
        // Given: 동네 조회가 아직 응답하지 않는다 — "로딩 중" 순간을 붙잡기 위한 게이트다
        val 동네응답게이트 = CompletableDeferred<Unit>()
        coEvery { memberRepository.getMyLocations() } coAnswers {
            동네응답게이트.await()
            Result.success(listOf(MemberLocation(region = RegionRef(code = "11680", name = "강남구", fullName = "서울특별시 강남구"), sortOrder = 0, active = true)))
        }
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns
            Result.success(ProductPage(items = listOf(상품(11L)), nextCursor = 11L, hasNext = false))

        // When
        val viewModel = 홈화면을_연다()

        // Then
        viewModel.uiState.test {
            assertEquals(HomeUiState.Loading, awaitItem())

            동네응답게이트.complete(Unit)

            assertTrue(awaitItem() is HomeUiState.Success)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `첫 진입에 성공하면 서버가 준 상품 목록이 순서 그대로 화면 상태에 담긴다`() = runTest {
        // Given
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(
            ProductPage(
                items = listOf(상품(11L), 상품(12L), 상품(13L)),
                nextCursor = 13L,
                hasNext = true,
            ),
        )

        // When
        val viewModel = 홈화면을_연다()

        // Then
        val state = viewModel.uiState.value as HomeUiState.Success
        assertEquals(listOf(11L, 12L, 13L), state.products.map { it.productId })
    }

    @Test
    fun `첫 진입에 성공하면 카테고리 칩이 서버 순서 그대로 화면 상태에 담긴다`() = runTest {
        // Given
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(첫페이지_마지막)

        // When
        val viewModel = 홈화면을_연다()

        // Then
        val state = viewModel.uiState.value as HomeUiState.Success
        assertEquals(listOf("디지털기기", "생활가전", "가구_인테리어", "의류"), state.categories.map { it.name })
    }

    @Test
    fun `첫 진입에 성공하면 대표 동네 이름이 헤더용으로 상태에 담긴다`() = runTest {
        // Given: 대표 동네(active)가 리스트의 첫 원소가 아니어도 골라내야 한다
        coEvery { memberRepository.getMyLocations() } returns Result.success(
            listOf(
                MemberLocation(region = RegionRef(code = "11440", name = "마포구", fullName = "서울특별시 마포구"), sortOrder = 1, active = false),
                MemberLocation(region = RegionRef(code = "11680", name = "강남구", fullName = "서울특별시 강남구"), sortOrder = 0, active = true),
            ),
        )
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(첫페이지_마지막)

        // When
        val viewModel = 홈화면을_연다()

        // Then
        val state = viewModel.uiState.value as HomeUiState.Success
        assertEquals("강남구", state.region)
    }

    // ─────────────────────── 2. 실패 정책(셋이 서로 다르다) ───────────────────────

    @Test
    fun `상품 조회가 실패하면 화면 전체가 Error 가 된다`() = runTest {
        // Given: 본문이 없으면 홈이 성립하지 않는다
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns
            Result.failure(AppError.Network())

        // When
        val viewModel = 홈화면을_연다()

        // Then: 백엔드 ErrorCode 가 아니라 사람이 읽는 문장이 나와야 한다
        assertEquals(HomeUiState.Error("네트워크 연결을 확인해 주세요."), viewModel.uiState.value)
    }

    @Test
    fun `카테고리 조회가 실패해도 상품 목록은 그대로 보인다`() = runTest {
        // Given: 칩은 부가 정보다 — 칩이 없다고 본문을 가리면 안 된다
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.failure(AppError.Network())
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(
            ProductPage(items = listOf(상품(11L), 상품(12L)), nextCursor = 12L, hasNext = false),
        )

        // When
        val viewModel = 홈화면을_연다()

        // Then
        val state = viewModel.uiState.value as HomeUiState.Success
        assertEquals(listOf(11L, 12L), state.products.map { it.productId })
    }

    @Test
    fun `카테고리 조회가 실패하면 칩 영역만 빈다`() = runTest {
        // Given
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.failure(AppError.Network())
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(첫페이지_마지막)

        // When
        val viewModel = 홈화면을_연다()

        // Then
        val state = viewModel.uiState.value as HomeUiState.Success
        assertEquals(emptyList<Category>(), state.categories)
    }

    @Test
    fun `동네 조회가 실패하면 헤더에 찍을 동네 이름이 없다`() = runTest {
        // Given: 로그인하지 않은 사용자가 홈을 여는 경로가 이것이다
        coEvery { memberRepository.getMyLocations() } returns Result.failure(AppError.Unauthorized())
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(첫페이지_마지막)

        // When
        val viewModel = 홈화면을_연다()

        // Then
        val state = viewModel.uiState.value as HomeUiState.Success
        assertNull(state.region)
    }

    @Test
    fun `동네 조회가 실패하면 지역 필터 없이 전국으로 상품을 조회한다`() = runTest {
        // Given
        coEvery { memberRepository.getMyLocations() } returns Result.failure(AppError.Unauthorized())
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(첫페이지_마지막)

        // When
        홈화면을_연다()

        // Then: regions 를 빈 리스트가 아니라 null 로 넘겨야 파라미터 자체가 생략된다(= 전국)
        coVerify(exactly = 1) {
            productRepository.getProducts(regionCodes = isNull(), cursor = isNull(), size = eq(30))
        }
    }

    @Test
    fun `내 동네가 3개여도 서버에는 우선순위 앞 2개만 전달된다`() = runTest {
        // Given: 서버는 지역 필터가 3개 이상이면 400 을 준다.
        //        리스트 순서는 뒤섞여 있고 sortOrder 가 진짜 우선순위다.
        coEvery { memberRepository.getMyLocations() } returns Result.success(
            listOf(
                MemberLocation(region = RegionRef(code = "11440", name = "마포구", fullName = "서울특별시 마포구"), sortOrder = 2, active = false),
                MemberLocation(region = RegionRef(code = "11680", name = "강남구", fullName = "서울특별시 강남구"), sortOrder = 0, active = true),
                MemberLocation(region = RegionRef(code = "11650", name = "서초구", fullName = "서울특별시 서초구"), sortOrder = 1, active = false),
            ),
        )
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(첫페이지_마지막)

        // When
        홈화면을_연다()

        // Then: sortOrder 로 정렬한 뒤 앞 2개
        coVerify(exactly = 1) {
            productRepository.getProducts(
                regionCodes = eq(listOf("11680", "11650")),
                cursor = isNull(),
                size = eq(30),
            )
        }
    }

    // ─────────────────────── 3. 필터(검색 · 카테고리 칩) ───────────────────────

    @Test
    fun `카테고리 칩을 선택하면 목록 API 가 아니라 검색 API 로 조회한다`() = runTest {
        // Given: 홈이 이미 떠 있다
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(첫페이지_마지막)
        coEvery { productRepository.searchProducts(any(), any(), any()) } returns
            Result.success(listOf(상품(21L)))
        val viewModel = 홈화면을_연다()

        // When: "생활가전"(id = 2) 칩을 누른다
        viewModel.onCategorySelect(2L)

        // Then: 카테고리 전용 엔드포인트는 regions 를 못 받아서 쓰지 않는다
        coVerify(exactly = 1) {
            productRepository.searchProducts(
                keyword = isNull(),
                categoryId = eq(2L),
                regionCodes = eq(listOf("11680")),
            )
        }
    }

    @Test
    fun `카테고리 칩을 선택하면 선택된 칩이 상태에 반영된다`() = runTest {
        // Given
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(첫페이지_마지막)
        coEvery { productRepository.searchProducts(any(), any(), any()) } returns
            Result.success(listOf(상품(21L)))
        val viewModel = 홈화면을_연다()

        // When
        viewModel.onCategorySelect(2L)

        // Then
        val state = viewModel.uiState.value as HomeUiState.Success
        assertEquals(2L, state.selectedCategoryId)
    }

    @Test
    fun `이미 선택된 칩을 다시 누르면 서버를 다시 부르지 않는다`() = runTest {
        // Given: "생활가전" 칩이 이미 선택된 상태
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(첫페이지_마지막)
        coEvery { productRepository.searchProducts(any(), any(), any()) } returns
            Result.success(listOf(상품(21L)))
        val viewModel = 홈화면을_연다()
        viewModel.onCategorySelect(2L)

        // When: 같은 칩을 한 번 더 누른다
        viewModel.onCategorySelect(2L)

        // Then: 왕복 한 번을 아끼고 스크롤 위치도 지키기 위해 아무 일도 하지 않는다
        coVerify(exactly = 1) { productRepository.searchProducts(any(), any(), any()) }
    }

    @Test
    fun `검색어를 확정하면 검색 API 로 조회한다`() = runTest {
        // Given
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(첫페이지_마지막)
        coEvery { productRepository.searchProducts(any(), any(), any()) } returns
            Result.success(listOf(상품(31L)))
        val viewModel = 홈화면을_연다()

        // When: 검색바에서 IME 검색 버튼을 눌렀다
        viewModel.onSearch("자전거")

        // Then
        coVerify(exactly = 1) {
            productRepository.searchProducts(
                keyword = eq("자전거"),
                categoryId = isNull(),
                regionCodes = eq(listOf("11680")),
            )
        }
    }

    @Test
    fun `검색어를 확정하면 확정된 검색어가 상태에 남는다`() = runTest {
        // Given
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(첫페이지_마지막)
        coEvery { productRepository.searchProducts(any(), any(), any()) } returns
            Result.success(listOf(상품(31L)))
        val viewModel = 홈화면을_연다()

        // When
        viewModel.onSearch("자전거")

        // Then: 검색바가 검색어를 다시 그려야 하므로 상태에 남아 있어야 한다
        val state = viewModel.uiState.value as HomeUiState.Success
        assertEquals("자전거", state.keyword)
    }

    @Test
    fun `검색 모드로 들어가면 다음 페이지가 없다고 표시된다`() = runTest {
        // Given: 기본 목록에는 다음 페이지가 있었다(hasNext = true)
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(
            ProductPage(items = listOf(상품(11L)), nextCursor = 11L, hasNext = true),
        )
        coEvery { productRepository.searchProducts(any(), any(), any()) } returns
            Result.success(listOf(상품(31L), 상품(32L)))
        val viewModel = 홈화면을_연다()

        // When
        viewModel.onSearch("자전거")

        // Then: 검색 엔드포인트에는 페이징이 없으므로 무한스크롤을 켜면 안 된다
        val state = viewModel.uiState.value as HomeUiState.Success
        assertFalse(state.hasNext)
    }

    @Test
    fun `카테고리 모드로 들어가면 다음 페이지가 없다고 표시된다`() = runTest {
        // Given: 기본 목록에는 다음 페이지가 있었다(hasNext = true)
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(
            ProductPage(items = listOf(상품(11L)), nextCursor = 11L, hasNext = true),
        )
        coEvery { productRepository.searchProducts(any(), any(), any()) } returns
            Result.success(listOf(상품(21L)))
        val viewModel = 홈화면을_연다()

        // When
        viewModel.onCategorySelect(2L)

        // Then
        val state = viewModel.uiState.value as HomeUiState.Success
        assertFalse(state.hasNext)
    }

    @Test
    fun `필터가 걸려 있는 동안에는 스크롤을 끝까지 내려도 다음 페이지를 부르지 않는다`() = runTest {
        // Given: 카테고리 칩이 선택된 상태(그리드는 조건을 따지지 않고 onLoadMore 를 부른다)
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(
            ProductPage(items = listOf(상품(11L)), nextCursor = 11L, hasNext = true),
        )
        coEvery { productRepository.searchProducts(any(), any(), any()) } returns
            Result.success(listOf(상품(21L)))
        val viewModel = 홈화면을_연다()
        viewModel.onCategorySelect(2L)

        // When
        viewModel.onLoadMore()

        // Then: 첫 진입 때의 1회 말고는 목록 API 가 불리지 않아야 한다
        coVerify(exactly = 1) { productRepository.getProducts(any(), any(), any()) }
    }

    // ─────────────────────── 4. 무한스크롤(커서 페이징) ───────────────────────

    @Test
    fun `다음 페이지를 받으면 기존 목록 뒤에 새 상품이 붙는다`() = runTest {
        // Given: 첫 페이지 [11, 12, 13] 이 떠 있고 다음 커서는 13 이다
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), isNull(), any()) } returns Result.success(
            ProductPage(items = listOf(상품(11L), 상품(12L), 상품(13L)), nextCursor = 13L, hasNext = true),
        )
        coEvery { productRepository.getProducts(any(), eq(13L), any()) } returns Result.success(
            ProductPage(items = listOf(상품(14L), 상품(15L)), nextCursor = 15L, hasNext = false),
        )
        val viewModel = 홈화면을_연다()

        // When: 그리드가 끝에 닿았다
        viewModel.onLoadMore()

        // Then
        val state = viewModel.uiState.value as HomeUiState.Success
        assertEquals(listOf(11L, 12L, 13L, 14L, 15L), state.products.map { it.productId })
    }

    @Test
    fun `두 페이지에 같은 상품이 걸쳐 오면 중복을 제거해 목록에 한 번만 남긴다`() = runTest {
        // Given: 커서 페이징 도중 상품이 등록·삭제되면 경계에서 같은 상품이 두 번 온다.
        //        LazyGrid 의 key 가 중복되면 즉시 크래시하므로 앱이 막아야 한다.
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), isNull(), any()) } returns Result.success(
            ProductPage(items = listOf(상품(11L), 상품(12L), 상품(13L)), nextCursor = 13L, hasNext = true),
        )
        coEvery { productRepository.getProducts(any(), eq(13L), any()) } returns Result.success(
            // 13 이 두 번째 페이지에도 다시 실려 왔다
            ProductPage(items = listOf(상품(13L), 상품(14L)), nextCursor = 14L, hasNext = false),
        )
        val viewModel = 홈화면을_연다()

        // When
        viewModel.onLoadMore()

        // Then
        val state = viewModel.uiState.value as HomeUiState.Success
        assertEquals(listOf(11L, 12L, 13L, 14L), state.products.map { it.productId })
    }

    @Test
    fun `다음 페이지를 받는 중에 또 요청이 들어와도 서버는 한 번만 부른다`() = runTest {
        // Given: 두 번째 페이지 응답이 아직 오지 않은 상태
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), isNull(), any()) } returns Result.success(
            ProductPage(items = listOf(상품(11L), 상품(12L)), nextCursor = 12L, hasNext = true),
        )
        coEvery { productRepository.getProducts(any(), eq(12L), any()) } coAnswers { awaitCancellation() }
        val viewModel = 홈화면을_연다()

        // When: 스크롤 한 번에 그리드가 onLoadMore 를 연달아 부른다
        viewModel.onLoadMore()
        viewModel.onLoadMore()

        // Then: isAppending 가드가 두 번째 요청을 막는다(같은 페이지 중복 로딩 방지)
        coVerify(exactly = 1) { productRepository.getProducts(any(), eq(12L), any()) }
    }

    @Test
    fun `다음 페이지 조회가 실패해도 이미 보고 있던 목록을 에러 화면으로 덮지 않는다`() = runTest {
        // Given: 첫 페이지는 떠 있고 두 번째 페이지가 실패한다
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), isNull(), any()) } returns Result.success(
            ProductPage(items = listOf(상품(11L), 상품(12L)), nextCursor = 12L, hasNext = true),
        )
        coEvery { productRepository.getProducts(any(), eq(12L), any()) } returns
            Result.failure(AppError.Network())
        val viewModel = 홈화면을_연다()

        // When
        viewModel.onLoadMore()

        // Then
        val state = viewModel.uiState.value as HomeUiState.Success
        assertEquals(listOf(11L, 12L), state.products.map { it.productId })
    }

    @Test
    fun `다음 페이지 조회가 끝나면 하단 스피너가 꺼진다`() = runTest {
        // Given
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), isNull(), any()) } returns Result.success(
            ProductPage(items = listOf(상품(11L)), nextCursor = 11L, hasNext = true),
        )
        coEvery { productRepository.getProducts(any(), eq(11L), any()) } returns
            Result.failure(AppError.Network())
        val viewModel = 홈화면을_연다()

        // When: 실패로 끝나도 스피너는 꺼져야 한다(안 꺼지면 재시도가 영영 막힌다)
        viewModel.onLoadMore()

        // Then
        val state = viewModel.uiState.value as HomeUiState.Success
        assertFalse(state.isAppending)
    }

    @Test
    fun `마지막 페이지까지 받았으면 더 이상 다음 페이지를 부르지 않는다`() = runTest {
        // Given: hasNext = false 인 한 페이지짜리 목록
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(
            ProductPage(items = listOf(상품(11L)), nextCursor = 11L, hasNext = false),
        )
        val viewModel = 홈화면을_연다()

        // When: nextCursor 가 남아 있어도(서버가 마지막 페이지에도 키를 남긴다) 부르면 안 된다
        viewModel.onLoadMore()

        // Then
        coVerify(exactly = 1) { productRepository.getProducts(any(), any(), any()) }
    }

    // ─────────────────────── 5. 재시도 ───────────────────────

    @Test
    fun `첫 로드 실패 후 재시도하면 이미 받아 둔 카테고리를 다시 받지 않는다`() = runTest {
        // Given: 동네·카테고리는 성공했고 상품만 실패해 Error 화면이 떠 있다
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns
            Result.failure(AppError.Network())
        val viewModel = 홈화면을_연다()
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(첫페이지_마지막)

        // When: "다시 시도" 를 누른다
        viewModel.onRetry()

        // Then: 목록만 다시 받는다
        coVerify(exactly = 1) { categoryRepository.getCategories() }
    }

    @Test
    fun `첫 로드 실패 후 재시도해서 성공하면 상품 목록이 보인다`() = runTest {
        // Given
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns
            Result.failure(AppError.Network())
        val viewModel = 홈화면을_연다()
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(
            ProductPage(items = listOf(상품(11L), 상품(12L)), nextCursor = 12L, hasNext = false),
        )

        // When
        viewModel.onRetry()

        // Then
        val state = viewModel.uiState.value as HomeUiState.Success
        assertEquals(listOf(11L, 12L), state.products.map { it.productId })
    }

    @Test
    fun `검색을 해제하면 다시 기본 목록 API 로 돌아간다`() = runTest {
        // Given: "자전거" 로 검색한 상태(검색바의 X 버튼이 빈 문자열을 준다)
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(첫페이지_마지막)
        coEvery { productRepository.searchProducts(any(), any(), any()) } returns
            Result.success(listOf(상품(31L)))
        val viewModel = 홈화면을_연다()
        viewModel.onSearch("자전거")

        // When
        viewModel.onSearch("")

        // Then: 첫 진입 1회 + 검색 해제 1회 = 목록 API 총 2회
        coVerify(exactly = 2) { productRepository.getProducts(any(), isNull(), any()) }
    }

    // ─────────────────────── 화면 복귀 ───────────────────────

    @Test
    fun `다른 화면에서 돌아오면 목록을 다시 받아 새 상품이 나타난다`() = runTest {
        // Given: 홈에 상품이 1건 있는 상태에서 사용자가 상품을 등록하고 돌아왔다
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(
            ProductPage(items = listOf(상품(11L)), nextCursor = 11L, hasNext = false),
        )
        val viewModel = 홈화면을_연다()
        // 그 사이 서버에는 방금 등록한 상품(99)이 생겼다
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(
            ProductPage(items = listOf(상품(99L), 상품(11L)), nextCursor = 11L, hasNext = false),
        )

        // When: 홈이 다시 화면 앞으로 나온다
        viewModel.onScreenResumed()

        // Then: 이게 없으면 방금 올린 물건이 목록에 없어 "등록이 실패한 것"으로 보인다.
        // 실제로는 서버에 잘 저장돼 있는데도. (2026-08-04 에뮬 실기 검수에서 발견)
        val state = viewModel.uiState.value as HomeUiState.Success
        assertEquals(listOf(99L, 11L), state.products.map { it.productId })
    }

    @Test
    fun `첫 진입이 끝나기 전의 복귀 신호는 중복 조회를 만들지 않는다`() = runTest {
        // Given: 동네 응답을 붙잡아 첫 로드를 진행 중으로 묶어 둔다
        val 동네응답게이트 = CompletableDeferred<Unit>()
        coEvery { memberRepository.getMyLocations() } coAnswers {
            동네응답게이트.await()
            Result.success(내동네_강남)
        }
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(첫페이지_마지막)
        val viewModel = 홈화면을_연다()

        // When: 첫 진입 직후 Compose 가 ON_RESUME 을 보낸다(실제로 이 순서로 일어난다)
        viewModel.onScreenResumed()
        동네응답게이트.complete(Unit)

        // Then: 첫 로드 1회뿐이어야 한다. 겹쳐 부르면 같은 목록을 두 번 받는다.
        coVerify(exactly = 1) { productRepository.getProducts(any(), isNull(), any()) }
    }

    @Test
    fun `복귀해도 사용자가 고른 카테고리 칩은 그대로 유지된다`() = runTest {
        // Given: 카테고리 필터를 걸어 둔 상태
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_강남)
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { productRepository.getProducts(any(), any(), any()) } returns Result.success(첫페이지_마지막)
        coEvery { productRepository.searchProducts(any(), any(), any()) } returns
            Result.success(listOf(상품(31L)))
        val viewModel = 홈화면을_연다()
        viewModel.onCategorySelect(2L)

        // When
        viewModel.onScreenResumed()

        // Then: 복귀가 필터를 초기화하면 사용자는 방금 고른 칩이 풀린 이유를 알 수 없다.
        // 화면 전체를 Loading 으로 되돌리지 않고 목록만 다시 받는 이유가 이것이다.
        val state = viewModel.uiState.value as HomeUiState.Success
        assertEquals(2L, state.selectedCategoryId)
    }

    // ─────────────────────── 테스트 데이터 ───────────────────────

    private val 내동네_강남 = listOf(
        MemberLocation(region = RegionRef(code = "11680", name = "강남구", fullName = "서울특별시 강남구"), sortOrder = 0, active = true),
    )

    private val 카테고리_4종 = listOf(
        Category(id = 1L, name = "디지털기기"),
        Category(id = 2L, name = "생활가전"),
        Category(id = 3L, name = "가구_인테리어"),
        Category(id = 4L, name = "의류"),
    )

    /** 다음 페이지가 없는 한 페이지짜리 목록. 목록 자체가 관심사가 아닌 테스트의 배경으로 쓴다. */
    private val 첫페이지_마지막 = ProductPage(
        items = listOf(상품(11L)),
        nextCursor = 11L,
        hasNext = false,
    )

    private fun 상품(productId: Long) = Product(
        productId = productId,
        sellerId = 7L,
        categoryId = 1L,
        title = "상품 $productId",
        price = BigDecimal("35000.00"),
        tradeStatus = TradeStatus.ON_SALE,
        region = RegionRef(code = "11680", name = "강남구", fullName = "서울특별시 강남구"),
        viewCount = 0L,
        favoriteCount = 0,
        thumbnailUrl = null,
        hidden = false,
    )
}

/**
 * `viewModelScope` 가 쓰는 `Dispatchers.Main` 을 테스트용으로 갈아 끼운다.
 *
 * 안 하면 ViewModel 생성 순간 "Module with the Main dispatcher had failed to initialize" 로 죽는다.
 * `UnconfinedTestDispatcher` 를 쓰므로 `viewModelScope.launch { }` 가 즉시 실행되어
 * 테스트에서 `advanceUntilIdle()` 없이 결과를 바로 읽을 수 있다.
 *
 * 이 파일 안에서만 쓰는 private 선언이다(다른 테스트 파일과 이름이 부딪히지 않는다).
 */
private class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
