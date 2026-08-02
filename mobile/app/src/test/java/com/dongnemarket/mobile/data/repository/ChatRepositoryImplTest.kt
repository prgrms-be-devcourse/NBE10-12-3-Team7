package com.dongnemarket.mobile.data.repository

import com.dongnemarket.mobile.data.remote.ChatApiService
import com.dongnemarket.mobile.data.remote.dto.ApiEnvelope
import com.dongnemarket.mobile.data.remote.dto.ChatMemberSummary
import com.dongnemarket.mobile.data.remote.dto.ChatMessagePageResponse
import com.dongnemarket.mobile.data.remote.dto.ChatMessageResponse
import com.dongnemarket.mobile.data.remote.dto.ChatProductDetail
import com.dongnemarket.mobile.data.remote.dto.ChatProductSummary
import com.dongnemarket.mobile.data.remote.dto.ChatRoomDetailResponse
import com.dongnemarket.mobile.data.remote.dto.ChatRoomListResponse
import com.dongnemarket.mobile.domain.model.AppError
import com.dongnemarket.mobile.domain.model.ChatMessage
import com.dongnemarket.mobile.domain.model.ChatRoomHeader
import com.dongnemarket.mobile.domain.model.TradeStatus
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.math.BigDecimal

/**
 * ## ChatRepositoryImpl 명세
 *
 * 이 Repository 가 흡수하는 서버의 "기괴한 점" 은 셋이고, 이 파일은 그 셋을 못 박는다.
 *  1. **메시지가 최신순(id DESC)으로 온다** → 화면이 쓰려면 시간 오름차순으로 뒤집어야 한다
 *  2. **방 단건 조회 API 가 없다** → `getRoomHeader` 는 목록을 받아 roomId 로 찾는 우회 구현이다
 *  3. **상대방 키가 응답마다 다르다** (생성 `seller` / 목록 `opponent`)
 *     → [ChatRoomHeader] 라는 한 타입으로 흡수한다
 *
 * 여기에 "성공 응답에 `data` 키가 없는 API"(`markAsRead`)를 성공으로 판정하는지도 함께 본다.
 */
class ChatRepositoryImplTest {

    private val api: ChatApiService = mockk()
    private val repository = ChatRepositoryImpl(api)

    // ── 1. createRoom: 방 전체 응답에서 roomId 만 꺼낸다 ───────────────────────────

    @Test
    fun `방을 만들면 응답에서 roomId 만 뽑아 돌려준다`() = runTest {
        // Given: 서버는 방 전체(상품 + 판매자)를 주지만 화면 이동에 필요한 것은 roomId 하나다
        coEvery { api.createRoom(any()) } returns ApiEnvelope(
            status = 200,
            data = roomDetailResponse(roomId = 5),
        )

        // When: 상품 상세에서 '채팅하기' 를 누른다
        val result = repository.createRoom(productId = 12)

        // Then: DTO 가 Data 계층 밖으로 새어 나가지 않는다
        assertEquals(5L, result.getOrNull())
    }

    @Test
    fun `자기 상품에 채팅을 걸면 CANNOT_CHAT_WITH_SELF 코드가 그대로 실려 온다`() = runTest {
        // Given: 판매자가 자기 상품으로 방을 만들려 한다
        coEvery { api.createRoom(any()) } throws serverError(
            status = 400,
            errorCode = "CANNOT_CHAT_WITH_SELF",
            message = "자기 자신과는 채팅할 수 없습니다.",
        )

        // When
        val result = repository.createRoom(productId = 12)

        // Then: 화면이 이 코드로 안내 문구를 고를 수 있어야 한다
        val error = result.exceptionOrNull()
        assertTrue(error is AppError.Api && error.code == "CANNOT_CHAT_WITH_SELF")
    }

    // ── 2. getMessages: 최신순으로 온 목록을 시간순으로 뒤집는다 ───────────────────

    @Test
    fun `서버가 최신순으로 준 메시지는 오래된 것에서 최신 순으로 뒤집혀 나온다`() = runTest {
        // Given: 서버는 id DESC(최신이 0번)로 준다
        coEvery { api.getMessages(roomId = 5, cursor = null, size = 30) } returns ApiEnvelope(
            status = 200,
            data = ChatMessagePageResponse(
                messages = listOf(
                    messageResponse(messageId = 103, content = "그럼 3시에 뵐게요"),
                    messageResponse(messageId = 102, content = "네 가능합니다"),
                    messageResponse(messageId = 101, content = "안녕하세요, 구매 가능할까요?"),
                ),
                nextCursor = 101,
                hasNext = true,
            ),
        )

        // When: 채팅방에 들어가 첫 페이지를 받는다(cursor 없음 = 최신 첫 페이지)
        val result = repository.getMessages(roomId = 5, cursor = null, size = 30)

        // Then: 뒤집지 않으면 대화가 거꾸로 보인다(위에서 아래로 시간이 흘러야 한다)
        assertEquals(
            listOf(
                "안녕하세요, 구매 가능할까요?",
                "네 가능합니다",
                "그럼 3시에 뵐게요",
            ),
            result.getOrThrow().messages.map { it.content },
        )
    }

    @Test
    fun `메시지를 뒤집어도 nextCursor 는 과거 방향 그대로 유지된다`() = runTest {
        // Given: nextCursor 는 이 페이지에서 가장 오래된 messageId 다
        coEvery { api.getMessages(roomId = 5, cursor = null, size = 30) } returns ApiEnvelope(
            status = 200,
            data = ChatMessagePageResponse(
                messages = listOf(messageResponse(103), messageResponse(102), messageResponse(101)),
                nextCursor = 101,
                hasNext = true,
            ),
        )

        // When
        val result = repository.getMessages(roomId = 5, cursor = null, size = 30)

        // Then: 뒤집기는 커서와 무관하다 — 여기서 흔들리면 '과거 더보기' 가 같은 페이지를 반복한다
        assertEquals(101L, result.getOrThrow().nextCursor)
    }

    @Test
    fun `과거가 더 없으면 hasNext 가 false 로 온다`() = runTest {
        // Given: 마지막 페이지
        coEvery { api.getMessages(roomId = 5, cursor = 101, size = 30) } returns ApiEnvelope(
            status = 200,
            data = ChatMessagePageResponse(
                messages = listOf(messageResponse(100)),
                nextCursor = null,
                hasNext = false,
            ),
        )

        // When: 직전 페이지의 nextCursor 로 과거를 더 부른다
        val result = repository.getMessages(roomId = 5, cursor = 101, size = 30)

        // Then
        assertEquals(false, result.getOrThrow().hasNext)
    }

    @Test
    fun `아직 아무 말도 없는 방은 빈 메시지 목록으로 온다`() = runTest {
        // Given: 갓 만든 방
        coEvery { api.getMessages(roomId = 5, cursor = null, size = 30) } returns ApiEnvelope(
            status = 200,
            data = ChatMessagePageResponse(messages = emptyList(), nextCursor = null, hasNext = false),
        )

        // When
        val result = repository.getMessages(roomId = 5, cursor = null, size = 30)

        // Then: 실패가 아니다 — 빈 방도 정상 상태다
        assertEquals(emptyList<ChatMessage>(), result.getOrThrow().messages)
    }

    // ── 3. getRoomHeader: 방 단건 API 가 없어서 목록에서 찾는다 ────────────────────

    @Test
    fun `방 헤더는 방 목록에서 roomId 로 찾아 만들어진다`() = runTest {
        // Given: 내 방이 두 개 있고 그중 5번을 연다
        coEvery { api.getRooms() } returns ApiEnvelope(
            status = 200,
            data = listOf(
                roomListResponse(roomId = 4, opponentId = 9, opponentNickname = "옆동네"),
                roomListResponse(roomId = 5, opponentId = 7, opponentNickname = "판매자닉"),
            ),
        )

        // When
        val result = repository.getRoomHeader(roomId = 5)

        // Then
        assertEquals(
            ChatRoomHeader(
                roomId = 5,
                opponentId = 7,
                opponentNickname = "판매자닉",
                productId = 12,
                productTitle = "자전거 팝니다",
                productPrice = BigDecimal("800000.00"),
                productTradeStatus = TradeStatus.ON_SALE,
                productThumbnailUrl = "https://cdn.example.com/thumb.jpg",
            ),
            result.getOrNull(),
        )
    }

    @Test
    fun `목록에 없는 roomId 면 404 CHAT_ROOM_NOT_FOUND 실패를 돌려준다`() = runTest {
        // Given: 내가 참여자가 아닌 방(또는 없는 방)
        coEvery { api.getRooms() } returns ApiEnvelope(
            status = 200,
            data = listOf(roomListResponse(roomId = 4, opponentId = 9, opponentNickname = "옆동네")),
        )

        // When
        val result = repository.getRoomHeader(roomId = 999)

        // Then: null 이나 빈 헤더로 뭉개면 화면이 열려선 안 되는 방을 연다.
        //       서버가 그 상황에 쓰는 코드를 그대로 달아 UI 가 구분하지 않아도 되게 한다.
        val error = result.exceptionOrNull()
        assertTrue(
            error is AppError.Api && error.status == 404 && error.code == "CHAT_ROOM_NOT_FOUND",
        )
    }

    @Test
    fun `방 목록 조회가 401 로 실패하면 그 실패를 그대로 전달한다`() = runTest {
        // Given: 토큰 만료
        coEvery { api.getRooms() } throws serverError(401, "INVALID_TOKEN", "다시 로그인해 주세요.")

        // When
        val result = repository.getRoomHeader(roomId = 5)

        // Then: 여기서 404 로 바꿔치기하면 "로그인 만료" 라는 진짜 원인이 사라진다
        assertTrue(result.exceptionOrNull() is AppError.Unauthorized)
    }

    // ── 4. seller / opponent 키 비대칭 흡수 ───────────────────────────────────────

    @Test
    fun `방 생성 응답의 seller 는 목록 응답의 opponent 와 같은 헤더로 흡수된다`() = runTest {
        // Given: 상품 상세 → 채팅하기. 생성 응답은 상대방을 seller 로, 목록 응답은 opponent 로 준다
        val created = roomDetailResponse(roomId = 5)
        coEvery { api.createRoom(any()) } returns ApiEnvelope(status = 200, data = created)
        coEvery { api.getRooms() } returns ApiEnvelope(
            status = 200,
            data = listOf(
                roomListResponse(
                    roomId = 5,
                    opponentId = created.seller.memberId,
                    opponentNickname = created.seller.nickname,
                ),
            ),
        )

        // When: 방을 만들고(roomId 만 얻고) 헤더를 채운다
        val roomId = repository.createRoom(productId = 12).getOrThrow()
        val header = repository.getRoomHeader(roomId).getOrThrow()

        // Then: 어느 응답에서 왔든 화면은 ChatRoomHeader 한 타입만 알면 된다
        assertEquals(
            ChatRoomHeader(
                roomId = created.roomId,
                opponentId = created.seller.memberId,          // 생성 응답 키: seller
                opponentNickname = created.seller.nickname,    // 목록 응답 키: opponent
                productId = created.product.productId,
                productTitle = created.product.title,
                productPrice = created.product.price,
                productTradeStatus = created.product.tradeStatus,
                productThumbnailUrl = created.product.thumbnailUrl,
            ),
            header,
        )
    }

    // ── 5. markAsRead: data 키가 없는 성공 응답 ───────────────────────────────────

    @Test
    fun `읽음 처리는 응답에 data 키가 없어도 성공으로 판정된다`() = runTest {
        // Given: 서버 ApiResponse 에 @JsonInclude(NON_NULL) 이 걸려 있어 data 키 자체가 사라진다
        coEvery { api.markAsRead(5) } returns ApiEnvelope(
            status = 200,
            message = "요청이 성공적으로 처리되었습니다.",
        )

        // When
        val result = repository.markAsRead(roomId = 5)

        // Then: apiCall 로 감쌌다면 "data 가 null" → EmptyBody 실패로 오판했을 자리다
        assertTrue(result.isSuccess)
    }

    @Test
    fun `읽음 처리가 404 면 실패로 돌려준다`() = runTest {
        // Given: 없는 방
        coEvery { api.markAsRead(999) } throws serverError(
            status = 404,
            errorCode = "CHAT_ROOM_NOT_FOUND",
            message = "채팅방을 찾을 수 없습니다.",
        )

        // When
        val result = repository.markAsRead(roomId = 999)

        // Then: 성공을 실패로도, 실패를 성공으로도 만들지 않는다
        assertTrue(result.isFailure)
    }

    // ── 6. 나머지 여정: 목록 조회와 전송 ──────────────────────────────────────────

    @Test
    fun `방 목록의 중첩 응답은 평평한 ChatRoom 으로 펼쳐진다`() = runTest {
        // Given: 마지막 메시지가 있는 방
        coEvery { api.getRooms() } returns ApiEnvelope(
            status = 200,
            data = listOf(
                roomListResponse(roomId = 5, opponentId = 7, opponentNickname = "판매자닉").copy(
                    lastMessage = messageResponse(messageId = 103, content = "그럼 3시에 뵐게요"),
                    unreadCount = 2,
                ),
            ),
        )

        // When
        val result = repository.getRooms()

        // Then: 화면이 room.product.thumbnailUrl 같은 점 사슬을 타지 않아도 된다
        val room = result.getOrThrow().single()
        assertEquals("자전거 팝니다", room.productTitle)
    }

    @Test
    fun `마지막 메시지가 없는 빈 방은 lastMessage 가 null 로 온다`() = runTest {
        // Given: 갓 만든 방(키는 남고 값만 null 로 온다)
        coEvery { api.getRooms() } returns ApiEnvelope(
            status = 200,
            data = listOf(roomListResponse(roomId = 5, opponentId = 7, opponentNickname = "판매자닉")),
        )

        // When
        val result = repository.getRooms()

        // Then
        assertEquals(null, result.getOrThrow().single().lastMessage)
    }

    @Test
    fun `메시지를 보내면 저장된 메시지가 도메인 모델로 돌아온다`() = runTest {
        // Given: 서버가 201 과 함께 저장된 메시지를 그대로 준다
        coEvery { api.sendMessage(roomId = 5, body = any()) } returns ApiEnvelope(
            status = 201,
            data = ChatMessageResponse(
                messageId = 104,
                senderId = 3,
                content = "지금 출발합니다",
                createdAt = "2026-07-27T14:02:11.501",
            ),
        )

        // When
        val result = repository.sendMessage(roomId = 5, content = "지금 출발합니다")

        // Then: 재조회 없이 그대로 목록에 붙일 수 있어야 한다
        assertEquals(
            ChatMessage(
                messageId = 104,
                senderId = 3,
                content = "지금 출발합니다",
                createdAt = "2026-07-27T14:02:11.501",
            ),
            result.getOrNull(),
        )
    }

    // ── 테스트 지원 ────────────────────────────────────────────────────────────────

    /** `POST /api/chat-rooms` 응답. 상대방 키가 **seller** 다. */
    private fun roomDetailResponse(roomId: Long) = ChatRoomDetailResponse(
        roomId = roomId,
        product = ChatProductDetail(
            productId = 12,
            title = "자전거 팝니다",
            description = "3년 탄 로드 자전거입니다.",
            price = BigDecimal("800000.00"),
            tradeStatus = TradeStatus.ON_SALE,
            regionCode = "11680",
            regionName = "강남구",
            regionFullName = "서울특별시 강남구",
            thumbnailUrl = "https://cdn.example.com/thumb.jpg",
        ),
        seller = ChatMemberSummary(memberId = 7, nickname = "판매자닉"),
    )

    /** `GET /api/chat-rooms` 응답 원소. 상대방 키가 **opponent** 다. */
    private fun roomListResponse(
        roomId: Long,
        opponentId: Long,
        opponentNickname: String,
    ) = ChatRoomListResponse(
        roomId = roomId,
        product = ChatProductSummary(
            productId = 12,
            title = "자전거 팝니다",
            price = BigDecimal("800000.00"),
            tradeStatus = TradeStatus.ON_SALE,
            thumbnailUrl = "https://cdn.example.com/thumb.jpg",
        ),
        opponent = ChatMemberSummary(memberId = opponentId, nickname = opponentNickname),
        createdAt = "2026-07-27T13:40:00.100",
        lastMessage = null,
        unreadCount = 0,
    )

    /** 메시지 한 건. 시각은 서버가 주는 원문 그대로(오프셋 없음, 소수부 가변). */
    private fun messageResponse(
        messageId: Long,
        content: String = "내용 $messageId",
        senderId: Long = 7,
    ) = ChatMessageResponse(
        messageId = messageId,
        senderId = senderId,
        content = content,
        createdAt = "2026-07-27T13:45:30.123",
    )

    /** 백엔드 `ErrorResponse` 본문을 실은 진짜 [HttpException]. */
    private fun serverError(status: Int, errorCode: String?, message: String): HttpException {
        val errorField = errorCode?.let { "\"$it\"" } ?: "null"
        val body = """
            {"status":$status,"error":$errorField,"message":"$message","timestamp":"2026-07-27T10:00:00"}
        """.trimIndent().toResponseBody("application/json".toMediaType())
        return HttpException(Response.error<Any>(status, body))
    }
}
