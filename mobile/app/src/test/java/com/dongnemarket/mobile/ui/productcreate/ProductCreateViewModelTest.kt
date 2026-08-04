package com.dongnemarket.mobile.ui.productcreate

import com.dongnemarket.mobile.domain.model.AppError
import com.dongnemarket.mobile.domain.model.Category
import com.dongnemarket.mobile.domain.model.MemberLocation
import com.dongnemarket.mobile.domain.model.NewProduct
import com.dongnemarket.mobile.domain.model.RegionRef
import com.dongnemarket.mobile.domain.repository.CategoryRepository
import com.dongnemarket.mobile.domain.repository.MemberRepository
import com.dongnemarket.mobile.domain.repository.ProductRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.math.BigDecimal

/**
 * 상품 등록 화면 상태 보유자([ProductCreateViewModel])의 명세.
 *
 * ## 이 화면이 다른 화면보다 검증 가치가 큰 이유
 * 등록은 **되돌릴 수 없는 쓰기**이고, 그마저도 요청이 두 단계(사진 업로드 → 상품 생성)로 갈라져 있다.
 * 그래서 다음 두 가지가 조용히 틀리면 사용자가 복구할 방법이 없다.
 *
 *  1. **검증이 업로드보다 늦으면** — 사진을 다 올린 뒤 "제목이 비었습니다"로 실패한다.
 *     이미 올라간 파일을 지우는 API 가 없어 서버에 고아 파일로 남는다.
 *  2. **실패 후 단계를 되돌리지 않으면** — 폼이 잠긴 채로 남아 고칠 수도 다시 보낼 수도 없다.
 *
 * 검증은 전부 **공개 API(`uiState` + 이벤트 함수)** 로만 한다.
 */
class ProductCreateViewModelTest {

    // 타입을 TestWatcher 로 적는 이유: 구현 클래스가 이 파일 전용 private 이라
    // 공개 프로퍼티가 private 타입을 노출하면 컴파일되지 않는다.
    @get:Rule
    val mainDispatcherRule: TestWatcher = MainDispatcherRule()

    private val productRepository = mockk<ProductRepository>()
    private val categoryRepository = mockk<CategoryRepository>()
    private val memberRepository = mockk<MemberRepository>()

    // ─────────────────────── 9. 폼 초기 로딩 ───────────────────────

    @Test
    fun `등록 화면에 들어가면 카테고리와 내 동네를 받아 폼을 연다`() = runTest {
        // Given
        폼_조회가_성공한다()

        // When
        val viewModel = 등록화면을_연다()

        // Then
        val state = viewModel.uiState.value
        assertFalse(state.isLoadingForm)
        assertNull(state.formLoadError)
        assertEquals(카테고리_4종, state.categories)
        assertEquals(내동네_둘, state.myLocations)
    }

    @Test
    fun `동네가 둘이면 대표 동네가 기본으로 선택된다`() = runTest {
        // Given: 0번이 역삼동(비대표), 1번이 삼성동(대표)이다 — 목록 순서와 대표가 일부러 어긋나 있다.
        // 그래야 "첫 원소를 골랐을 뿐"인 구현과 "active 를 골랐다"는 구현이 구분된다.
        폼_조회가_성공한다(
            locations = listOf(
                내동네(code = "1168010300", name = "역삼동", sortOrder = 1, active = false),
                내동네(code = "1168010600", name = "삼성동", sortOrder = 0, active = true),
            ),
        )

        // When
        val viewModel = 등록화면을_연다()

        // Then
        assertEquals("1168010600", viewModel.uiState.value.selectedRegionCode)
    }

    @Test
    fun `동네를 설정하지 않은 회원은 폼 대신 안내를 받는다`() = runTest {
        // Given: 미설정은 에러가 아니라 빈 리스트로 온다(성공 응답이다).
        폼_조회가_성공한다(locations = emptyList())

        // When
        val viewModel = 등록화면을_연다()

        // Then: regionCode 의 출처가 없으므로 등록이 원천적으로 불가능하다 → 제출 자체를 막는다
        val state = viewModel.uiState.value
        assertTrue(state.hasNoRegion)
        assertFalse(state.isSubmitEnabled)
        assertNull(state.selectedRegionCode)
    }

    @Test
    fun `내 동네 조회가 실패하면 폼을 열지 않고 에러를 보여 준다`() = runTest {
        // Given: 카테고리는 성공했지만 동네가 실패했다
        coEvery { categoryRepository.getCategories() } returns Result.success(카테고리_4종)
        coEvery { memberRepository.getMyLocations() } returns
            Result.failure(AppError.Network())

        // When
        val viewModel = 등록화면을_연다()

        // Then: 하나라도 없으면 등록 요청을 만들 수 없다 → 부분 성공으로 넘어가지 않는다
        val state = viewModel.uiState.value
        assertFalse(state.isLoadingForm)
        assertEquals("네트워크 연결을 확인해 주세요.", state.formLoadError)
    }

    @Test
    fun `폼을 여는 동안에는 로딩 상태로 남는다`() = runTest {
        // Given: 카테고리 응답을 붙잡아 둔다
        val 게이트 = CompletableDeferred<Unit>()
        coEvery { memberRepository.getMyLocations() } returns Result.success(내동네_둘)
        coEvery { categoryRepository.getCategories() } coAnswers {
            게이트.await()
            Result.success(카테고리_4종)
        }

        // When
        val viewModel = 등록화면을_연다()

        // Then
        assertTrue(viewModel.uiState.value.isLoadingForm)
        게이트.complete(Unit)
        assertFalse(viewModel.uiState.value.isLoadingForm)
    }

    // ─────────────────────── 4. 대표 사진 인덱스 보정 ───────────────────────

    @Test
    fun `대표보다 앞의 사진을 지우면 대표는 같은 사진을 계속 가리킨다`() = runTest {
        // Given: [A, B, C] 에서 C(2번)가 대표
        val viewModel = 사진이_준비된_화면(listOf("A", "B", "C"), thumbnailIndex = 2)

        // When: A 를 지운다 → [B, C]
        viewModel.onRemoveImage(0)

        // Then: C 는 이제 1번이다. 그대로 2를 들고 있으면 서버가 400 을 준다.
        val state = viewModel.uiState.value
        assertEquals(listOf("B", "C"), state.imageUris)
        assertEquals(1, state.thumbnailIndex)
    }

    @Test
    fun `대표 자신을 지우면 그 자리로 밀려 올라온 사진이 대표가 된다`() = runTest {
        // Given: [A, B, C] 에서 B(1번)가 대표
        val viewModel = 사진이_준비된_화면(listOf("A", "B", "C"), thumbnailIndex = 1)

        // When: B 를 지운다 → [A, C]
        viewModel.onRemoveImage(1)

        // Then: 같은 자리(1번 = C)를 대표로 유지한다 — 사용자가 마지막으로 본 위치다
        val state = viewModel.uiState.value
        assertEquals(listOf("A", "C"), state.imageUris)
        assertEquals(1, state.thumbnailIndex)
    }

    @Test
    fun `마지막 사진이자 대표를 지우면 대표가 남은 마지막 사진으로 당겨진다`() = runTest {
        // Given: [A, B] 에서 B(1번)가 대표 — 지우면 인덱스가 범위를 벗어나는 경계다
        val viewModel = 사진이_준비된_화면(listOf("A", "B"), thumbnailIndex = 1)

        // When
        viewModel.onRemoveImage(1)

        // Then: 1 로 남으면 imageUrls.size(1) 를 넘어 400 INVALID_INPUT_VALUE 다
        val state = viewModel.uiState.value
        assertEquals(listOf("A"), state.imageUris)
        assertEquals(0, state.thumbnailIndex)
    }

    @Test
    fun `대표보다 뒤의 사진을 지우면 대표 인덱스는 그대로다`() = runTest {
        // Given: [A, B, C] 에서 A(0번)가 대표
        val viewModel = 사진이_준비된_화면(listOf("A", "B", "C"), thumbnailIndex = 0)

        // When
        viewModel.onRemoveImage(2)

        // Then
        val state = viewModel.uiState.value
        assertEquals(listOf("A", "B"), state.imageUris)
        assertEquals(0, state.thumbnailIndex)
    }

    @Test
    fun `사진을 전부 지우면 대표 인덱스는 0 으로 돌아간다`() = runTest {
        // Given
        val viewModel = 사진이_준비된_화면(listOf("A"), thumbnailIndex = 0)

        // When
        viewModel.onRemoveImage(0)

        // Then: 다음에 사진을 다시 고를 때 유효한 시작점이어야 한다
        val state = viewModel.uiState.value
        assertTrue(state.imageUris.isEmpty())
        assertEquals(0, state.thumbnailIndex)
    }

    @Test
    fun `사진 선택 결과는 덧붙이지 않고 통째로 교체된다`() = runTest {
        // Given: 이미 A, B 를 골라 둔 상태
        val viewModel = 사진이_준비된_화면(listOf("A", "B"), thumbnailIndex = 0)

        // When: 시스템 선택기가 "기존 + 새것" 전체를 돌려준다
        viewModel.onImagesPicked(listOf("A", "B", "C"))

        // Then: 덧붙였다면 A·B 가 두 번씩 들어가 5장 제한과 대표 인덱스가 동시에 망가진다
        assertEquals(listOf("A", "B", "C"), viewModel.uiState.value.imageUris)
    }

    @Test
    fun `사진을 상한보다 많이 고르면 앞에서 5장만 남는다`() = runTest {
        // Given
        폼_조회가_성공한다()
        val viewModel = 등록화면을_연다()

        // When: 구버전 폴백(문서 선택기)에는 maxItems 제한이 없어 실제로 들어올 수 있다
        viewModel.onImagesPicked(listOf("A", "B", "C", "D", "E", "F", "G"))

        // Then: 6장 이상을 보내면 서버가 400 INVALID_INPUT_VALUE 를 준다
        assertEquals(listOf("A", "B", "C", "D", "E"), viewModel.uiState.value.imageUris)
    }

    // ─────────────────────── 8. 가격 입력 필터 ───────────────────────

    @Test
    fun `가격에 콤마나 글자를 입력해도 숫자만 남는다`() = runTest {
        // Given
        폼_조회가_성공한다()
        val viewModel = 등록화면을_연다()

        // When: 붙여넣기로 "35,000원" 이 통째로 들어온 상황
        viewModel.onPriceChange("35,000원")

        // Then: BigDecimal 로 바꿀 수 있는 문자열만 상태에 남는다
        assertEquals("35000", viewModel.uiState.value.priceInput)
        assertEquals(BigDecimal("35000"), viewModel.uiState.value.parsedPrice)
    }

    @Test
    fun `가격을 비우면 파싱 결과가 null 이 되고 0 으로 대체되지 않는다`() = runTest {
        // Given
        폼_조회가_성공한다()
        val viewModel = 등록화면을_연다()
        viewModel.onPriceChange("35000")

        // When: 사용자가 지웠다
        viewModel.onPriceChange("")

        // Then: 0 으로 대체하면 "지운 칸"이 "0원(나눔)"으로 보인다 — 서로 다른 뜻이다
        assertEquals("", viewModel.uiState.value.priceInput)
        assertNull(viewModel.uiState.value.parsedPrice)
    }

    @Test
    fun `가격 자리수가 지나치게 길면 잘린다`() = runTest {
        // Given
        폼_조회가_성공한다()
        val viewModel = 등록화면을_연다()

        // When: 20자리를 밀어 넣는다
        viewModel.onPriceChange("1".repeat(20))

        // Then: 서버 제약이 아니라 화면이 밀려나지 않게 하는 방어다
        assertEquals(12, viewModel.uiState.value.priceInput.length)
    }

    // ─────────────────────── 5·6. 검증과 그 순서 ───────────────────────

    @Test
    fun `필수 항목이 모두 비면 다섯 칸 각각에 이유가 붙는다`() = runTest {
        // Given: 동네만 자동 선택된 빈 폼
        폼_조회가_성공한다()
        val viewModel = 등록화면을_연다()

        // When
        viewModel.onSubmit()

        // Then: 서버는 이 위반들을 전부 INVALID_INPUT_VALUE 하나로 뭉쳐 준다 →
        // 어느 칸이 문제인지는 앱만 알 수 있고, 앱이 말하지 않으면 아무도 모른다.
        val errors = viewModel.uiState.value.fieldErrors
        assertEquals("사진을 1장 이상 등록해 주세요.", errors.images)
        assertEquals("제목을 입력해 주세요.", errors.title)
        assertEquals("가격을 입력해 주세요.", errors.price)
        assertEquals("카테고리를 선택해 주세요.", errors.category)
        // 동네는 폼을 열 때 대표 동네가 자동 선택되므로 비어 있지 않다
        assertNull(errors.region)
    }

    @Test
    fun `제목이 공백뿐이면 입력한 것으로 보지 않는다`() = runTest {
        // Given: 서버는 hasText() 로 검사해 공백만 있는 제목을 400 INVALID_PRODUCT_TITLE 로 거부한다
        val viewModel = 제출_직전_상태(title = "   ")

        // When
        viewModel.onSubmit()

        // Then
        assertEquals("제목을 입력해 주세요.", viewModel.uiState.value.fieldErrors.title)
    }

    @Test
    fun `0원은 나눔이므로 검증을 통과한다`() = runTest {
        // Given: 서버 조건이 signum() >= 0 이라 0 은 허용된다.
        // "가격이 없다"와 "0원이다"를 같이 취급하면 나눔 등록이 막힌다.
        val viewModel = 제출_직전_상태(price = "0")
        등록이_성공한다(productId = 77L)

        // When
        viewModel.onSubmit()

        // Then
        assertNull(viewModel.uiState.value.fieldErrors.price)
        assertEquals(CreatePhase.Done(77L), viewModel.uiState.value.phase)
    }

    @Test
    fun `검증에 걸리면 서버를 한 번도 호출하지 않는다`() = runTest {
        // Given: 제목만 비어 있다. 나머지는 모두 유효하다.
        val viewModel = 제출_직전_상태(title = "")

        // When
        viewModel.onSubmit()

        // Then: 이 순서가 이 화면의 핵심 계약이다.
        // 업로드가 먼저 나가면 실패 시 서버에 고아 파일이 남고, 앱은 그걸 지울 수 없다.
        coVerify(exactly = 0) { productRepository.createProduct(any(), any()) }
        assertEquals(CreatePhase.Editing, viewModel.uiState.value.phase)
    }

    @Test
    fun `입력을 고치면 그 칸의 오류 문구는 사라진다`() = runTest {
        // Given: 제목 오류가 떠 있는 상태
        val viewModel = 제출_직전_상태(title = "")
        viewModel.onSubmit()
        assertNotNull(viewModel.uiState.value.fieldErrors.title)

        // When
        viewModel.onTitleChange("닌텐도 스위치")

        // Then: 고치는 중에도 빨간 글씨가 남아 있으면 무엇을 더 고쳐야 하는지 알 수 없다
        assertNull(viewModel.uiState.value.fieldErrors.title)
    }

    // ─────────────────────── 7. 단계 전이 ───────────────────────

    @Test
    fun `등록에 성공하면 업로드 진행률을 거쳐 Done 으로 끝난다`() = runTest {
        // Given: 사진 3장. 저장소가 장당 한 번씩 진행률을 알려 준다.
        val viewModel = 제출_직전_상태(images = listOf("A", "B", "C"))
        val 관측된_단계 = mutableListOf<CreatePhase>()
        coEvery { productRepository.createProduct(any(), any()) } coAnswers {
            val 진행률통지 = secondArg<(Int, Int) -> Unit>()
            관측된_단계 += viewModel.uiState.value.phase
            진행률통지(1, 3); 관측된_단계 += viewModel.uiState.value.phase
            진행률통지(2, 3); 관측된_단계 += viewModel.uiState.value.phase
            진행률통지(3, 3); 관측된_단계 += viewModel.uiState.value.phase
            Result.success(42L)
        }

        // When
        viewModel.onSubmit()

        // Then: 마지막 장을 올린 순간 "사진 올리는 중 3/3" 이 아니라 "등록하는 중" 으로 넘어가야 한다 —
        // 그 시점에 실제로 진행되는 일이 상품 생성 요청이기 때문이다.
        assertEquals(
            listOf(
                CreatePhase.Uploading(done = 0, total = 3),
                CreatePhase.Uploading(done = 1, total = 3),
                CreatePhase.Uploading(done = 2, total = 3),
                CreatePhase.Creating,
            ),
            관측된_단계,
        )
        assertEquals(CreatePhase.Done(42L), viewModel.uiState.value.phase)
    }

    @Test
    fun `등록이 실패하면 반드시 편집 상태로 되돌아온다`() = runTest {
        // Given
        val viewModel = 제출_직전_상태()
        coEvery { productRepository.createProduct(any(), any()) } returns
            Result.failure(AppError.Api(status = 400, code = "INVALID_INPUT_VALUE", message = "잘못된 요청입니다."))

        // When
        viewModel.onSubmit()

        // Then: Uploading/Creating 에 멈춰 있으면 폼이 잠긴 채로 남아
        // 사용자가 고칠 수도, 다시 시도할 수도 없다. 이게 이 테스트의 존재 이유다.
        val state = viewModel.uiState.value
        assertEquals(CreatePhase.Editing, state.phase)
        assertTrue(state.isSubmitEnabled)
        assertFalse(state.isFormLocked)
        assertEquals("잘못된 요청입니다.", state.submitError)
    }

    @Test
    fun `실패해도 입력한 내용은 그대로 남는다`() = runTest {
        // Given
        val viewModel = 제출_직전_상태(title = "닌텐도 스위치", images = listOf("A", "B"))
        coEvery { productRepository.createProduct(any(), any()) } returns
            Result.failure(AppError.Network())

        // When
        viewModel.onSubmit()

        // Then: 실패했다고 폼을 비우면 사용자가 사진 선택부터 전부 다시 해야 한다
        val state = viewModel.uiState.value
        assertEquals("닌텐도 스위치", state.title)
        assertEquals(listOf("A", "B"), state.imageUris)
    }

    @Test
    fun `오류를 한 번 보여 준 뒤에는 지운다`() = runTest {
        // Given
        val viewModel = 제출_직전_상태()
        coEvery { productRepository.createProduct(any(), any()) } returns
            Result.failure(AppError.Network())
        viewModel.onSubmit()

        // When: 화면이 스낵바를 띄운 뒤 알려 준다
        viewModel.onSubmitErrorShown()

        // Then: 안 지우면 화면 회전 때마다 같은 스낵바가 다시 뜬다
        assertNull(viewModel.uiState.value.submitError)
    }

    @Test
    fun `진행 중에는 폼이 잠겨 입력이 바뀌지 않는다`() = runTest {
        // Given: 업로드 중간에 멈춰 세운다
        val viewModel = 제출_직전_상태(title = "원래 제목")
        val 게이트 = CompletableDeferred<Unit>()
        coEvery { productRepository.createProduct(any(), any()) } coAnswers {
            게이트.await()
            Result.success(1L)
        }
        viewModel.onSubmit()

        // When: 업로드 중에 제목을 고치고 사진을 지우려 한다
        viewModel.onTitleChange("바뀐 제목")
        viewModel.onRemoveImage(0)

        // Then: 이미 보낸 요청과 화면이 어긋나면 무엇이 등록됐는지 아무도 모르게 된다
        assertEquals("원래 제목", viewModel.uiState.value.title)
        assertEquals(listOf("A"), viewModel.uiState.value.imageUris)
        게이트.complete(Unit)
    }

    @Test
    fun `진행 중에 다시 제출해도 중복 등록되지 않는다`() = runTest {
        // Given
        val viewModel = 제출_직전_상태()
        val 게이트 = CompletableDeferred<Unit>()
        coEvery { productRepository.createProduct(any(), any()) } coAnswers {
            게이트.await()
            Result.success(1L)
        }
        viewModel.onSubmit()

        // When: 사용자가 버튼을 두 번 눌렀다
        viewModel.onSubmit()
        viewModel.onSubmit()

        // Then: 등록은 쓰기다. 두 번 나가면 같은 상품이 두 개 생긴다.
        게이트.complete(Unit)
        coVerify(exactly = 1) { productRepository.createProduct(any(), any()) }
    }

    // ─────────────────────── 제출 값 조립 ───────────────────────

    @Test
    fun `화면이 모은 값이 그대로 등록 요청이 된다`() = runTest {
        // Given
        폼_조회가_성공한다()
        val viewModel = 등록화면을_연다()
        viewModel.onImagesPicked(listOf("A", "B"))
        viewModel.onThumbnailSelect(1)
        viewModel.onTitleChange("거의 새것 닌텐도 스위치")
        viewModel.onDescriptionChange("작년에 샀어요")
        viewModel.onPriceChange("240,000")
        viewModel.onCategorySelect(3L)
        viewModel.onRegionSelect("1168010600")

        val 요청 = slot<NewProduct>()
        coEvery { productRepository.createProduct(capture(요청), any()) } returns Result.success(9L)

        // When
        viewModel.onSubmit()

        // Then
        assertEquals(
            NewProduct(
                title = "거의 새것 닌텐도 스위치",
                description = "작년에 샀어요",
                price = BigDecimal("240000"),
                categoryId = 3L,
                regionCode = "1168010600",
                imageUris = listOf("A", "B"),
                thumbnailIndex = 1,
            ),
            요청.captured,
        )
    }

    @Test
    fun `설명을 비워도 null 이 아니라 빈 문자열로 나간다`() = runTest {
        // Given: 서버는 description 을 검증 없이 역참조한다(Product.create(..., request.description!!, ...)).
        // null 이면 400 이 아니라 NPE → 500 이고, 앱의 Json 은 explicitNulls=false 라 null 이면 키가 통째로 빠진다.
        val viewModel = 제출_직전_상태(description = "")
        val 요청 = slot<NewProduct>()
        coEvery { productRepository.createProduct(capture(요청), any()) } returns Result.success(1L)

        // When
        viewModel.onSubmit()

        // Then
        assertEquals("", 요청.captured.description)
    }

    // ─────────────────────── 픽스처 ───────────────────────

    private fun 등록화면을_연다() =
        ProductCreateViewModel(productRepository, categoryRepository, memberRepository)

    private fun 폼_조회가_성공한다(
        categories: List<Category> = 카테고리_4종,
        locations: List<MemberLocation> = 내동네_둘,
    ) {
        coEvery { categoryRepository.getCategories() } returns Result.success(categories)
        coEvery { memberRepository.getMyLocations() } returns Result.success(locations)
    }

    private fun 등록이_성공한다(productId: Long) {
        coEvery { productRepository.createProduct(any(), any()) } returns Result.success(productId)
    }

    /** 사진만 세팅된 화면. 대표 인덱스 보정 테스트의 배경이다. */
    private fun 사진이_준비된_화면(uris: List<String>, thumbnailIndex: Int): ProductCreateViewModel {
        폼_조회가_성공한다()
        return 등록화면을_연다().apply {
            onImagesPicked(uris)
            onThumbnailSelect(thumbnailIndex)
        }
    }

    /**
     * 제출 버튼만 누르면 되는 상태. 인자로 준 것만 바꿔서
     * "이 값 하나 때문에 결과가 달라졌다"를 분명히 한다.
     */
    private fun 제출_직전_상태(
        images: List<String> = listOf("A"),
        title: String = "닌텐도 스위치",
        price: String = "240000",
        description: String = "설명",
        categoryId: Long = 1L,
    ): ProductCreateViewModel {
        폼_조회가_성공한다()
        return 등록화면을_연다().apply {
            onImagesPicked(images)
            onTitleChange(title)
            onPriceChange(price)
            onDescriptionChange(description)
            onCategorySelect(categoryId)
        }
    }

    private val 카테고리_4종 = listOf(
        Category(id = 1L, name = "디지털기기"),
        Category(id = 2L, name = "생활가전"),
        Category(id = 3L, name = "가구_인테리어"),
        Category(id = 4L, name = "의류"),
    )

    private val 내동네_둘 = listOf(
        내동네(code = "1168010300", name = "역삼동", sortOrder = 0, active = true),
        내동네(code = "1168010600", name = "삼성동", sortOrder = 1, active = false),
    )

    private fun 내동네(code: String, name: String, sortOrder: Int, active: Boolean) = MemberLocation(
        region = RegionRef(code = code, name = name, fullName = "서울특별시 강남구 $name"),
        sortOrder = sortOrder,
        active = active,
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
