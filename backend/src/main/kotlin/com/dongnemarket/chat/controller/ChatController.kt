package com.dongnemarket.chat.controller

import com.dongnemarket.chat.dto.ChatMessageCreateRequest
import com.dongnemarket.chat.dto.ChatMessagePageResponse
import com.dongnemarket.chat.dto.ChatMessageResponse
import com.dongnemarket.chat.dto.ChatReadReceiptResponse
import com.dongnemarket.chat.dto.ChatRoomCreateRequest
import com.dongnemarket.chat.dto.ChatRoomDetailResponse
import com.dongnemarket.chat.dto.ChatRoomListResponse
import com.dongnemarket.chat.service.ChatService
import com.dongnemarket.global.response.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Chat", description = "1:1 채팅 API")
@RestController
class ChatController(
    private val chatService: ChatService,
    private val messagingTemplate: SimpMessagingTemplate,
) {
    @Operation(summary = "채팅방 연결", description = "상품에 대한 채팅방을 조회하거나 없으면 생성한다(get-or-create). 신규·기존 모두 200.")
    @PostMapping("/api/chat-rooms")
    fun createRoom(
        @AuthenticationPrincipal memberId: Long,
        @Valid @RequestBody request: ChatRoomCreateRequest,
    ): ResponseEntity<ApiResponse<ChatRoomDetailResponse>> {
        val response = chatService.createRoom(memberId, request.productId!!)
        return ResponseEntity.ok(ApiResponse.success("채팅방에 연결되었습니다.", response))
    }

    @Operation(summary = "내 채팅방 목록", description = "로그인 사용자가 참여한(구매자·판매자) 채팅방을 최근순으로 조회한다.")
    @GetMapping("/api/chat-rooms")
    fun getMyRooms(
        @AuthenticationPrincipal memberId: Long,
    ): ResponseEntity<ApiResponse<List<ChatRoomListResponse>>> = ResponseEntity.ok(ApiResponse.success(chatService.getMyRooms(memberId)))

    @Operation(summary = "메시지 조회", description = "방의 메시지를 최신순 커서 페이지네이션으로 조회한다. 참여자만 접근 가능.")
    @GetMapping("/api/chat-rooms/{roomId}/messages")
    fun getMessages(
        @AuthenticationPrincipal memberId: Long,
        @PathVariable roomId: Long,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "30") size: Int,
    ): ResponseEntity<ApiResponse<ChatMessagePageResponse>> =
        ResponseEntity.ok(ApiResponse.success(chatService.getMessages(memberId, roomId, cursor, size)))

    @Operation(summary = "메시지 전송", description = "방에 메시지를 전송한다. 참여자만 가능.")
    @PostMapping("/api/chat-rooms/{roomId}/messages")
    fun sendMessage(
        @AuthenticationPrincipal memberId: Long,
        @PathVariable roomId: Long,
        @Valid @RequestBody request: ChatMessageCreateRequest,
    ): ResponseEntity<ApiResponse<ChatMessageResponse>> {
        val response = chatService.sendMessage(memberId, roomId, request.content)
        // sendMessage는 @Transactional이라 여기(반환 후)는 이미 커밋된 시점 → 방 구독자에게 push. B안(REST 유지 + broadcast).
        messagingTemplate.convertAndSend(CHAT_ROOM_TOPIC_PREFIX + roomId, response)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.success(HttpStatus.CREATED.value(), "메시지를 전송했습니다.", response))
    }

    @Operation(summary = "읽음 처리", description = "방의 메시지를 모두 읽음 처리한다(내 읽음 지점을 최신 메시지로 이동). 참여자만 가능.")
    @PostMapping("/api/chat-rooms/{roomId}/read")
    fun markAsRead(
        @AuthenticationPrincipal memberId: Long,
        @PathVariable roomId: Long,
    ): ResponseEntity<ApiResponse<Void?>> {
        val lastReadMessageId = chatService.markRoomAsRead(memberId, roomId)
        // 읽음 지점이 실제로 전진했을 때만(재-read·빈 방이면 null) 방 구독자에게 읽음 영수증 push.
        // markRoomAsRead는 @Transactional이라 여기(반환 후)는 이미 커밋된 시점 → sendMessage와 동일 패턴.
        if (lastReadMessageId != null) {
            messagingTemplate.convertAndSend(
                CHAT_ROOM_TOPIC_PREFIX + roomId + "/read",
                ChatReadReceiptResponse.of(roomId, memberId, lastReadMessageId),
            )
        }
        return ResponseEntity.ok(ApiResponse.success<Void?>("읽음 처리되었습니다.", null))
    }

    companion object {
        /** 채팅방 토픽 목적지 접두사. 구독 인가(ChatSubscribeInterceptor)와 반드시 동일해야 한다. */
        private const val CHAT_ROOM_TOPIC_PREFIX = "/topic/chat-rooms/"
    }
}
