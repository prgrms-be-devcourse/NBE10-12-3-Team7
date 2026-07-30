package com.dongnemarket.chat.controller;

import com.dongnemarket.chat.dto.ChatMessageCreateRequest;
import com.dongnemarket.chat.dto.ChatMessagePageResponse;
import com.dongnemarket.chat.dto.ChatMessageResponse;
import com.dongnemarket.chat.dto.ChatReadReceiptResponse;
import com.dongnemarket.chat.dto.ChatRoomCreateRequest;
import com.dongnemarket.chat.dto.ChatRoomDetailResponse;
import com.dongnemarket.chat.dto.ChatRoomListResponse;
import com.dongnemarket.chat.service.ChatService;
import com.dongnemarket.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Chat", description = "1:1 채팅 API")
@RestController
public class ChatController {

    /** 채팅방 토픽 목적지 접두사. 구독 인가(ChatSubscribeInterceptor)와 반드시 동일해야 한다. */
    private static final String CHAT_ROOM_TOPIC_PREFIX = "/topic/chat-rooms/";

    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;

    public ChatController(ChatService chatService, SimpMessagingTemplate messagingTemplate) {
        this.chatService = chatService;
        this.messagingTemplate = messagingTemplate;
    }

    @Operation(summary = "채팅방 연결", description = "상품에 대한 채팅방을 조회하거나 없으면 생성한다(get-or-create). 신규·기존 모두 200.")
    @PostMapping("/api/chat-rooms")
    public ResponseEntity<ApiResponse<ChatRoomDetailResponse>> createRoom(
            @AuthenticationPrincipal Long memberId,
            @Valid @RequestBody ChatRoomCreateRequest request) {
        ChatRoomDetailResponse response = chatService.createRoom(memberId, request.getProductId());
        return ResponseEntity.ok(ApiResponse.success("채팅방에 연결되었습니다.", response));
    }

    @Operation(summary = "내 채팅방 목록", description = "로그인 사용자가 참여한(구매자·판매자) 채팅방을 최근순으로 조회한다.")
    @GetMapping("/api/chat-rooms")
    public ResponseEntity<ApiResponse<List<ChatRoomListResponse>>> getMyRooms(
            @AuthenticationPrincipal Long memberId) {
        return ResponseEntity.ok(ApiResponse.success(chatService.getMyRooms(memberId)));
    }

    @Operation(summary = "메시지 조회", description = "방의 메시지를 최신순 커서 페이지네이션으로 조회한다. 참여자만 접근 가능.")
    @GetMapping("/api/chat-rooms/{roomId}/messages")
    public ResponseEntity<ApiResponse<ChatMessagePageResponse>> getMessages(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long roomId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(ApiResponse.success(chatService.getMessages(memberId, roomId, cursor, size)));
    }

    @Operation(summary = "메시지 전송", description = "방에 메시지를 전송한다. 참여자만 가능.")
    @PostMapping("/api/chat-rooms/{roomId}/messages")
    public ResponseEntity<ApiResponse<ChatMessageResponse>> sendMessage(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long roomId,
            @Valid @RequestBody ChatMessageCreateRequest request) {
        ChatMessageResponse response = chatService.sendMessage(memberId, roomId, request.getContent());
        // sendMessage는 @Transactional이라 여기(반환 후)는 이미 커밋된 시점 → 방 구독자에게 push. B안(REST 유지 + broadcast).
        messagingTemplate.convertAndSend(CHAT_ROOM_TOPIC_PREFIX + roomId, response);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(HttpStatus.CREATED.value(), "메시지를 전송했습니다.", response));
    }

    @Operation(summary = "읽음 처리", description = "방의 메시지를 모두 읽음 처리한다(내 읽음 지점을 최신 메시지로 이동). 참여자만 가능.")
    @PostMapping("/api/chat-rooms/{roomId}/read")
    public ResponseEntity<ApiResponse<Void>> markAsRead(
            @AuthenticationPrincipal Long memberId,
            @PathVariable Long roomId) {
        Long lastReadMessageId = chatService.markRoomAsRead(memberId, roomId);
        // 읽음 지점이 실제로 전진했을 때만(재-read·빈 방이면 null) 방 구독자에게 읽음 영수증 push.
        // markRoomAsRead는 @Transactional이라 여기(반환 후)는 이미 커밋된 시점 → sendMessage와 동일 패턴.
        if (lastReadMessageId != null) {
            messagingTemplate.convertAndSend(CHAT_ROOM_TOPIC_PREFIX + roomId + "/read",
                    ChatReadReceiptResponse.of(roomId, memberId, lastReadMessageId));
        }
        return ResponseEntity.ok(ApiResponse.<Void>success("읽음 처리되었습니다.", null));
    }
}
