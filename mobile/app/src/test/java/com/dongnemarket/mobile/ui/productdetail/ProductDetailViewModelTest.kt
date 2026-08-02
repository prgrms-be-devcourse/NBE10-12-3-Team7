package com.dongnemarket.mobile.ui.productdetail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.dongnemarket.mobile.domain.model.AppError
import com.dongnemarket.mobile.domain.model.Category
import com.dongnemarket.mobile.domain.model.Member
import com.dongnemarket.mobile.domain.model.MemberRole
import com.dongnemarket.mobile.domain.model.MemberStatus
import com.dongnemarket.mobile.domain.model.ProductDetail
import com.dongnemarket.mobile.domain.model.TradeStatus
import com.dongnemarket.mobile.domain.model.RegionRef
import com.dongnemarket.mobile.domain.repository.CategoryRepository
import com.dongnemarket.mobile.domain.repository.ChatRepository
import com.dongnemarket.mobile.domain.repository.FavoriteRepository
import com.dongnemarket.mobile.domain.repository.MemberRepository
import com.dongnemarket.mobile.domain.repository.ProductRepository
import com.dongnemarket.mobile.ui.navigation.MarketOnRoutes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.math.BigDecimal

/**
 * 상품 상세 화면([ProductDetailViewModel])의 상태 명세서.
 *
 * 이 화면의 어려운 지점 두 가지를 중심으로 읽으면 된다.
 *  1. **하트 초기값은 상세 응답이 아니라 찜 캐시에서 온다** — 서버 상세 응답에 '내가 찜했는지' 가 없다.
 *  2. **찜 탭은 낙관적 갱신이다** — 서버 응답 전에 화면을 먼저 바꾸고, 실패하면 되돌린다.
 *     되돌리지 못하면 "찜했는데 목록에 없다" 가 되므로 롤백이 이 화면의 핵심 방어다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductDetailViewModelTest {

    /**
     * `viewModelScope` 는 `Dispatchers.Main` 위에서 돈다. 단위 테스트 JVM 에는 Main(안드로이드 메인 루퍼)이
     * 없어서 갈아 끼우지 않으면 ViewModel 생성 즉시 예외가 난다.
     * `UnconfinedTestDispatcher` 를 쓰면 코루틴이 즉시 실행되므로 "탭 → 상태" 를 한 줄씩 확인할 수 있다.
     */
    class MainDispatcherRule(
        val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
    ) : TestWatcher() {
        override fun starting(description: Description) = Dispatchers.setMain(testDispatcher)
        override fun finished(description: Description) = Dispatchers.resetMain()
    }

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val productRepository = mockk<ProductRepository>()
    private val favoriteRepository = mockk<FavoriteRepository>()
    private val categoryRepository = mockk<CategoryRepository>()
    private val memberRepository = mockk<MemberRepository>()
    private val chatRepository = mockk<ChatRepository>()

    /** 앱 전역 찜 캐시를 흉내 낸 것. 실제 구현도 이렇게 `StateFlow<Set<Long>>` 하나로 앱 전체가 공유한다. */
    private val favoriteCache = MutableStateFlow<Set<Long>>(emptySet())

    /** 서버가 주는 상세 응답 1건. 찜 12개짜리 자전거. */
    private val bicycle = ProductDetail(
        productId = PRODUCT_ID,
        sellerId = SELLER_ID,
        sellerNickname = "강남이웃",
        categoryId = CATEGORY_ID,
        title = "삼천리 로드 자전거 팝니다",
        description = "3개월 탔고 흠집 없습니다.",
        price = BigDecimal("120000.00"),
        tradeStatus = TradeStatus.ON_SALE,
        region = RegionRef(code = "11680", name = "강남구", fullName = "서울특별시 강남구"),
        viewCount = 31,
        favoriteCount = 12,
        thumbnailUrl = "http://10.0.2.2:8080/images/bike-1.jpg",
        imageUrls = listOf("http://10.0.2.2:8080/images/bike-1.jpg"),
        hidden = false,
    )

    /** 로그인한 나. 자전거 판매자(id 7)와 다른 사람이므로 기본 시나리오에서는 '내 상품' 이 아니다. */
    private val me = Member(
        memberId = MY_MEMBER_ID,
        email = "me@dongne.com",
        nickname = "나",
        role = MemberRole.USER,
        status = MemberStatus.ACTIVE,
        createdAt = "2026-07-01T10:00:00",
    )

    /** 모든 테스트의 출발점: 로그인 상태이고 서버가 전부 정상 응답한다. 각 테스트는 필요한 것만 덮어쓴다. */
    @Before
    fun `로그인 상태이고 서버가 모두 정상 응답하는 상황`() {
        every { favoriteRepository.favoriteProductIds } returns favoriteCache
        coEvery { favoriteRepository.refreshFavorites() } returns Result.success(Unit)
        coEvery { favoriteRepository.addFavorite(any()) } returns Result.success(Unit)
        coEvery { favoriteRepository.removeFavorite(any()) } returns Result.success(Unit)
        coEvery { memberRepository.getMyProfile() } returns Result.success(me)
        coEvery { categoryRepository.getCategories() } returns
            Result.success(listOf(Category(id = CATEGORY_ID, name = "스포츠/레저")))
        coEvery { productRepository.getProductDetail(PRODUCT_ID) } returns Result.success(bicycle)
        coEvery { chatRepository.createRoom(PRODUCT_ID) } returns Result.success(ROOM_ID)
    }

    // ──────────────────────────────────────────────────────────────
    // 진입 — 상세 로드
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `상세 응답이 도착하기 전까지는 Loading 이고 도착하면 Success 로 바뀐다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 서버 응답을 우리가 원할 때 도착시키기 위해 상세 조회를 붙잡아 둔다
            val 서버응답도착 = CompletableDeferred<Unit>()
            coEvery { productRepository.getProductDetail(PRODUCT_ID) } coAnswers {
                서버응답도착.await()
                Result.success(bicycle)
            }

            // When — 목록에서 상품 카드를 탭해 상세로 진입
            val viewModel = 상세화면진입()

            // Then
            viewModel.uiState.test {
                assertEquals(ProductDetailUiState.Loading, awaitItem())

                서버응답도착.complete(Unit)

                assertTrue(awaitItem() is ProductDetailUiState.Success)
            }
        }

    @Test
    fun `상세 조회에 성공하면 서버가 준 상품이 그대로 화면에 담긴다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 기본 상황(정상 응답)

            // When
            val viewModel = 상세화면진입()

            // Then
            assertEquals(bicycle, viewModel.성공상태().product)
        }

    @Test
    fun `categoryId 에 해당하는 카테고리 이름이 채워진다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 서버 카테고리 목록에 이 상품의 categoryId(6) 가 '스포츠_레저' 로 들어 있다

            // When
            val viewModel = 상세화면진입()

            // Then — 상세 응답에는 숫자 id 만 오므로 목록에서 찾아 채운 값이다
            assertEquals("스포츠/레저", viewModel.성공상태().categoryName)
        }

    @Test
    fun `카테고리 조회가 실패해도 상세 본문은 Success 로 보인다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 카테고리 API 만 죽은 상황
            coEvery { categoryRepository.getCategories() } returns
                Result.failure(AppError.Network())

            // When
            val viewModel = 상세화면진입()

            // Then — 부속 정보 실패가 화면 전체를 에러로 덮지 않는다
            val state = viewModel.uiState.value
            assertTrue("카테고리 실패가 본문까지 에러로 만들었다: $state", state is ProductDetailUiState.Success)
        }

    @Test
    fun `카테고리 조회가 실패하면 카테고리 이름 칸은 비어 있다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given
            coEvery { categoryRepository.getCategories() } returns
                Result.failure(AppError.Network())

            // When
            val viewModel = 상세화면진입()

            // Then — null 이면 화면이 그 조각을 아예 그리지 않는다
            assertNull(viewModel.성공상태().categoryName)
        }

    @Test
    fun `삭제된 상품이라 404 가 오면 Error 상태가 된다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 목록에서 방금 본 상품도 상세에서 404 가 날 수 있다(삭제·거래완료·판매자 탈퇴)
            coEvery { productRepository.getProductDetail(PRODUCT_ID) } returns Result.failure(
                AppError.Api(status = 404, code = "PRODUCT_NOT_FOUND", message = "상품을 찾을 수 없습니다."),
            )

            // When
            val viewModel = 상세화면진입()

            // Then
            assertEquals(
                ProductDetailUiState.Error("삭제되었거나 거래가 끝난 상품이에요."),
                viewModel.uiState.value,
            )
        }

    @Test
    fun `404 에러 문구에 백엔드 error 코드가 노출되지 않는다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given
            coEvery { productRepository.getProductDetail(PRODUCT_ID) } returns Result.failure(
                AppError.Api(status = 404, code = "PRODUCT_NOT_FOUND", message = "상품을 찾을 수 없습니다."),
            )

            // When
            val viewModel = 상세화면진입()

            // Then — 개발자 용어가 사용자 화면에 새어 나가면 안 된다
            val message = (viewModel.uiState.value as ProductDetailUiState.Error).message
            assertFalse("에러 코드가 그대로 노출됐다: $message", message.contains("PRODUCT_NOT_FOUND"))
        }

    @Test
    fun `숨김 상품이라 403 이 오면 판매자가 숨겼다고 안내한다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given
            coEvery { productRepository.getProductDetail(PRODUCT_ID) } returns Result.failure(
                AppError.Api(status = 403, code = "PRODUCT_ACCESS_DENIED", message = "접근할 수 없습니다."),
            )

            // When
            val viewModel = 상세화면진입()

            // Then
            assertEquals(
                ProductDetailUiState.Error("판매자가 숨긴 상품이에요."),
                viewModel.uiState.value,
            )
        }

    @Test
    fun `경로 인자가 깨져 productId 가 없으면 서버를 부르지 않고 에러로 끝낸다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — NavHost 인자가 유실된 상태
            val 인자없음 = SavedStateHandle()

            // When
            val viewModel = 상세화면진입(savedStateHandle = 인자없음)

            // Then — 이 GET 은 조회수를 올리는 쓰기 동작이라, 의미 없는 호출을 애초에 내보내지 않는다
            coVerify(exactly = 0) { productRepository.getProductDetail(any()) }
            assertTrue(viewModel.uiState.value is ProductDetailUiState.Error)
        }

    // ──────────────────────────────────────────────────────────────
    // 찜 — 초기값은 캐시에서 온다
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `찜 캐시에 이 상품이 있으면 하트가 켜진 채로 시작한다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 내 찜 목록을 새로 받아 왔더니 이 상품이 들어 있다
            //         (상세 응답에는 '내가 찜했는지' 가 없어서 이 캐시가 유일한 판정 근거다)
            coEvery { favoriteRepository.refreshFavorites() } coAnswers {
                favoriteCache.value = setOf(PRODUCT_ID, 1001L)
                Result.success(Unit)
            }

            // When
            val viewModel = 상세화면진입()

            // Then
            assertTrue(viewModel.성공상태().isFavorite)
        }

    @Test
    fun `찜 캐시에 이 상품이 없으면 하트가 꺼진 채로 시작한다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 남의 상품만 찜해 둔 상태
            coEvery { favoriteRepository.refreshFavorites() } coAnswers {
                favoriteCache.value = setOf(1001L, 1002L)
                Result.success(Unit)
            }

            // When
            val viewModel = 상세화면진입()

            // Then
            assertFalse(viewModel.성공상태().isFavorite)
        }

    // ──────────────────────────────────────────────────────────────
    // 찜 탭 — 낙관적 갱신과 롤백
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `하트를 누르면 찜 API 응답을 기다리지 않고 즉시 켜진다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 찜 등록 요청을 서버 왕복 중인 상태로 붙잡아 둔다
            val 서버응답도착 = CompletableDeferred<Unit>()
            coEvery { favoriteRepository.addFavorite(PRODUCT_ID) } coAnswers {
                서버응답도착.await()
                Result.success(Unit)
            }
            val viewModel = 상세화면진입()

            // When
            viewModel.onFavoriteClick()

            // Then — 아직 서버는 아무 답도 주지 않았는데 하트가 켜져 있어야 한다
            assertFalse("테스트 전제가 깨졌다: 찜 API 가 이미 끝났다", 서버응답도착.isCompleted)
            assertTrue(viewModel.성공상태().isFavorite)

            서버응답도착.complete(Unit)
        }

    @Test
    fun `하트를 누르면 찜 개수가 즉시 1 증가한다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 찜 12개인 자전거
            val viewModel = 상세화면진입()
            assertEquals(12, viewModel.성공상태().favoriteCount)

            // When
            viewModel.onFavoriteClick()

            // Then — 찜 등록 응답에는 갱신된 개수가 오지 않으므로 로컬로 ±1 한다
            assertEquals(13, viewModel.성공상태().favoriteCount)
        }

    @Test
    fun `이미 찜한 상품의 하트를 누르면 찜 개수가 즉시 1 감소한다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 이미 찜한 상태로 진입
            coEvery { favoriteRepository.refreshFavorites() } coAnswers {
                favoriteCache.value = setOf(PRODUCT_ID)
                Result.success(Unit)
            }
            val viewModel = 상세화면진입()

            // When
            viewModel.onFavoriteClick()

            // Then
            assertEquals(11, viewModel.성공상태().favoriteCount)
        }

    @Test
    fun `이미 찜한 상품의 하트를 누르면 찜 취소 API 가 호출된다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given
            coEvery { favoriteRepository.refreshFavorites() } coAnswers {
                favoriteCache.value = setOf(PRODUCT_ID)
                Result.success(Unit)
            }
            val viewModel = 상세화면진입()

            // When
            viewModel.onFavoriteClick()

            // Then — 등록이 아니라 취소로 나가야 한다
            coVerify(exactly = 1) { favoriteRepository.removeFavorite(PRODUCT_ID) }
            coVerify(exactly = 0) { favoriteRepository.addFavorite(any()) }
        }

    @Test
    fun `찜 등록이 실패하면 하트가 원래대로 꺼진다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 지하철에서 네트워크가 끊긴 상황
            coEvery { favoriteRepository.addFavorite(PRODUCT_ID) } returns
                Result.failure(AppError.Network())
            val viewModel = 상세화면진입()

            // When
            viewModel.onFavoriteClick()

            // Then — 롤백하지 않으면 화면과 서버가 영구히 어긋난다("찜했는데 목록에 없다")
            assertFalse(viewModel.성공상태().isFavorite)
        }

    @Test
    fun `찜 등록이 실패하면 찜 개수도 원래 값으로 돌아온다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given
            coEvery { favoriteRepository.addFavorite(PRODUCT_ID) } returns
                Result.failure(AppError.Network())
            val viewModel = 상세화면진입()

            // When
            viewModel.onFavoriteClick()

            // Then
            assertEquals(12, viewModel.성공상태().favoriteCount)
        }

    @Test
    fun `찜 등록이 실패하면 실패 사유가 일회성 메시지로 남는다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given
            coEvery { favoriteRepository.addFavorite(PRODUCT_ID) } returns
                Result.failure(AppError.Network())
            val viewModel = 상세화면진입()

            // When
            viewModel.onFavoriteClick()

            // Then — 조용히 되돌리기만 하면 사용자는 찜이 왜 풀렸는지 알 수 없다
            assertEquals("네트워크 연결을 확인해 주세요.", viewModel.성공상태().message)
        }

    @Test
    fun `찜 취소가 실패하면 하트가 다시 켜진 상태로 돌아온다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 찜한 상품에서 취소를 눌렀는데 서버가 실패
            coEvery { favoriteRepository.refreshFavorites() } coAnswers {
                favoriteCache.value = setOf(PRODUCT_ID)
                Result.success(Unit)
            }
            coEvery { favoriteRepository.removeFavorite(PRODUCT_ID) } returns
                Result.failure(AppError.Network())
            val viewModel = 상세화면진입()

            // When
            viewModel.onFavoriteClick()

            // Then
            assertTrue(viewModel.성공상태().isFavorite)
            assertEquals(12, viewModel.성공상태().favoriteCount)
        }

    @Test
    fun `찜 요청이 끝나기 전에 연타해도 찜 API 는 한 번만 호출된다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 서버 왕복 중
            val 서버응답도착 = CompletableDeferred<Unit>()
            coEvery { favoriteRepository.addFavorite(PRODUCT_ID) } coAnswers {
                서버응답도착.await()
                Result.success(Unit)
            }
            val viewModel = 상세화면진입()

            // When — 응답이 오기 전에 세 번 탭
            viewModel.onFavoriteClick()
            viewModel.onFavoriteClick()
            viewModel.onFavoriteClick()

            // Then — 등록/취소가 교차해 나가면 서버 최종 상태를 예측할 수 없다
            coVerify(exactly = 1) { favoriteRepository.addFavorite(PRODUCT_ID) }
            coVerify(exactly = 0) { favoriteRepository.removeFavorite(any()) }

            서버응답도착.complete(Unit)
        }

    @Test
    fun `찜 요청이 끝나기 전에 연타해도 찜 개수는 한 번만 증가한다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given
            val 서버응답도착 = CompletableDeferred<Unit>()
            coEvery { favoriteRepository.addFavorite(PRODUCT_ID) } coAnswers {
                서버응답도착.await()
                Result.success(Unit)
            }
            val viewModel = 상세화면진입()

            // When
            viewModel.onFavoriteClick()
            viewModel.onFavoriteClick()
            viewModel.onFavoriteClick()

            // Then — 12 → 13 이지 15 가 아니다
            assertEquals(13, viewModel.성공상태().favoriteCount)

            서버응답도착.complete(Unit)
        }

    @Test
    fun `다른 화면에서 찜을 바꾸면 이 화면의 하트도 따라 켜진다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 하트가 꺼진 상태로 상세를 보고 있다
            val viewModel = 상세화면진입()
            assertFalse(viewModel.성공상태().isFavorite)

            // When — 홈 목록 카드에서 이 상품을 찜해 전역 캐시가 바뀌었다
            favoriteCache.value = setOf(PRODUCT_ID)

            // Then — 앱 전체가 같은 찜 상태를 본다
            assertTrue(viewModel.성공상태().isFavorite)
        }

    @Test
    fun `찜 토글 중에 도착한 전역 캐시 값은 방금 켠 하트를 다시 끄지 않는다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 서버 왕복 중인 찜 등록
            val 서버응답도착 = CompletableDeferred<Unit>()
            coEvery { favoriteRepository.addFavorite(PRODUCT_ID) } coAnswers {
                서버응답도착.await()
                Result.success(Unit)
            }
            val viewModel = 상세화면진입()
            viewModel.onFavoriteClick()

            // When — 다른 화면이 갱신한(아직 이 상품이 없는) 옛 캐시가 도착
            favoriteCache.value = setOf(1001L)

            // Then — 낙관적으로 켠 하트가 깜빡이며 꺼지면 안 된다
            assertTrue(viewModel.성공상태().isFavorite)

            서버응답도착.complete(Unit)
        }

    // ──────────────────────────────────────────────────────────────
    // 채팅하기
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `채팅하기에 성공하면 서버가 준 방 id 가 이동 이벤트로 나간다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given
            val viewModel = 상세화면진입()

            // When / Then
            viewModel.chatRoomEvent.test {
                viewModel.onChatClick()

                assertEquals(ROOM_ID, awaitItem())
            }
        }

    @Test
    fun `채팅방 생성에 실패하면 이동 이벤트가 발생하지 않는다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 그 사이 판매자가 상품을 지웠다
            coEvery { chatRepository.createRoom(PRODUCT_ID) } returns Result.failure(
                AppError.Api(status = 404, code = "PRODUCT_NOT_FOUND", message = "상품을 찾을 수 없습니다."),
            )
            val viewModel = 상세화면진입()

            // When / Then — 없는 방으로 이동하면 채팅방 화면이 다시 404 를 만난다
            viewModel.chatRoomEvent.test {
                viewModel.onChatClick()

                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `채팅방 생성에 실패하면 실패 사유가 메시지로 남는다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given
            coEvery { chatRepository.createRoom(PRODUCT_ID) } returns Result.failure(
                AppError.Api(status = 404, code = "PRODUCT_NOT_FOUND", message = "상품을 찾을 수 없습니다."),
            )
            val viewModel = 상세화면진입()

            // When
            viewModel.onChatClick()

            // Then
            assertEquals("삭제되었거나 거래가 끝난 상품이에요.", viewModel.성공상태().message)
        }

    @Test
    fun `채팅방 생성에 실패하면 버튼 스피너가 풀려 다시 시도할 수 있다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given
            coEvery { chatRepository.createRoom(PRODUCT_ID) } returns
                Result.failure(AppError.Network())
            val viewModel = 상세화면진입()

            // When
            viewModel.onChatClick()

            // Then — 여기서 true 로 남으면 버튼이 영영 죽는다
            assertFalse(viewModel.성공상태().isChatCreating)
        }

    // ──────────────────────────────────────────────────────────────
    // 내 상품 — 판매자는 자기 상품에 채팅방을 못 만든다
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `내가 판매자인 상품이면 isMyProduct 가 true 라 채팅 버튼이 비활성이 된다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 내 memberId 가 이 상품의 sellerId 와 같다
            coEvery { memberRepository.getMyProfile() } returns
                Result.success(me.copy(memberId = SELLER_ID))

            // When
            val viewModel = 상세화면진입()

            // Then
            assertTrue(viewModel.성공상태().isMyProduct)
        }

    @Test
    fun `내 상품에서 채팅하기가 눌려도 채팅방 생성 API 를 부르지 않는다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 버튼은 비활성이지만 접근성 도구 등으로 눌릴 수 있다
            coEvery { memberRepository.getMyProfile() } returns
                Result.success(me.copy(memberId = SELLER_ID))
            val viewModel = 상세화면진입()

            // When
            viewModel.onChatClick()

            // Then — 보내 봤자 400 CANNOT_CHAT_WITH_SELF 인 요청은 애초에 내보내지 않는다
            coVerify(exactly = 0) { chatRepository.createRoom(any()) }
        }

    @Test
    fun `내 상품에서 채팅하기가 눌리면 이유를 안내한다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given
            coEvery { memberRepository.getMyProfile() } returns
                Result.success(me.copy(memberId = SELLER_ID))
            val viewModel = 상세화면진입()

            // When
            viewModel.onChatClick()

            // Then
            assertEquals("내 상품에는 채팅을 걸 수 없어요.", viewModel.성공상태().message)
        }

    @Test
    fun `로그인하지 않아 내 정보를 못 받으면 내 상품 판정을 하지 않는다`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Given — 토큰 만료
            coEvery { memberRepository.getMyProfile() } returns
                Result.failure(AppError.Unauthorized())

            // When
            val viewModel = 상세화면진입()

            // Then — 판정 불가면 버튼을 살려 두고 서버 응답에 맡긴다
            assertFalse(viewModel.성공상태().isMyProduct)
        }

    // ──────────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────────

    /** `productDetail/{productId}` 경로로 화면에 진입하는 것과 같다(NavHost 가 인자를 넣어 준다). */
    private fun 상세화면진입(
        savedStateHandle: SavedStateHandle = SavedStateHandle(
            mapOf(MarketOnRoutes.ARG_PRODUCT_ID to PRODUCT_ID),
        ),
    ): ProductDetailViewModel = ProductDetailViewModel(
        savedStateHandle = savedStateHandle,
        productRepository = productRepository,
        favoriteRepository = favoriteRepository,
        categoryRepository = categoryRepository,
        memberRepository = memberRepository,
        chatRepository = chatRepository,
    )

    /** 현재 상태가 Success 임을 전제로 꺼낸다. 아니면 그 자리에서 실패시켜 원인을 드러낸다. */
    private fun ProductDetailViewModel.성공상태(): ProductDetailUiState.Success =
        uiState.value as? ProductDetailUiState.Success
            ?: throw AssertionError("Success 를 기대했지만 실제 상태는 ${uiState.value} 였다")

    private companion object {
        const val PRODUCT_ID = 42L
        const val SELLER_ID = 7L
        const val MY_MEMBER_ID = 99L
        const val CATEGORY_ID = 6L
        const val ROOM_ID = 77L
    }
}
