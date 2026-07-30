package com.dongnemarket.chat.service;

import com.dongnemarket.chat.dto.ChatMessagePageResponse;
import com.dongnemarket.chat.dto.ChatMessageResponse;
import com.dongnemarket.chat.dto.ChatRoomDetailResponse;
import com.dongnemarket.chat.dto.ChatRoomListResponse;
import com.dongnemarket.chat.entity.ChatMessage;
import com.dongnemarket.chat.entity.ChatRoom;
import com.dongnemarket.chat.repository.ChatMessageRepository;
import com.dongnemarket.chat.repository.ChatRoomRepository;
import com.dongnemarket.chat.repository.RoomUnreadCount;
import com.dongnemarket.global.exception.BusinessException;
import com.dongnemarket.global.exception.ErrorCode;
import com.dongnemarket.member.entity.Member;
import com.dongnemarket.product.service.ProductService;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Limit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final int MAX_PAGE_SIZE = 100;

    private final ChatRoomCreator chatRoomCreator;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ProductService productService;
    private final EntityManager entityManager;

    public ChatService(ChatRoomCreator chatRoomCreator,
                       ChatRoomRepository chatRoomRepository,
                       ChatMessageRepository chatMessageRepository,
                       ProductService productService,
                       EntityManager entityManager) {
        this.chatRoomCreator = chatRoomCreator;
        this.chatRoomRepository = chatRoomRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.productService = productService;
        this.entityManager = entityManager;
    }

    /**
     * 상품에 대한 채팅방을 get-or-create 한다(멱등). 접근 불가 상품·자기 상품은 차단.
     * <p>쓰기는 {@link ChatRoomCreator}(독립 트랜잭션)에 위임하고, 동시 최초 생성 경쟁으로 INSERT가 실패하면
     * 그 트랜잭션 <b>바깥</b>에서(여기서) 이긴 방을 재조회한다 — rollback-only 트랜잭션 재사용을 피한다.
     * 메서드 자체엔 트랜잭션을 걸지 않아 위임 쓰기와 복구 조회가 서로 다른 트랜잭션에서 실행된다.
     */
    public ChatRoomDetailResponse createRoom(Long memberId, Long productId) {
        productService.validateAccessibleProduct(productId);
        try {
            chatRoomCreator.createIfAbsent(memberId, productId);
        } catch (DataIntegrityViolationException race) {
            // 경쟁에서 진 INSERT는 롤백됨. 이긴 방이 이미 존재하므로 아래 조회에서 가져온다.
        }
        ChatRoom room = chatRoomRepository.findDetailByProductAndBuyer(productId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        return ChatRoomDetailResponse.of(room);
    }

    /** 특정 상품에 채팅방을 연 구매자 id들. 가격 변경 알림 수신자(그 상품에 관심 있는 구매자) 조회용. */
    @Transactional(readOnly = true)
    public List<Long> findBuyerIdsForProduct(Long productId) {
        return chatRoomRepository.findBuyerIdsByProduct_Id(productId);
    }

    /** 내가 참여한 방 목록(최근 활동순 = 마지막 메시지 시각). 상품 요약·상대방·방별 마지막 메시지를 함께 담는다. */
    @Transactional(readOnly = true)
    public List<ChatRoomListResponse> getMyRooms(Long memberId) {
        List<ChatRoom> rooms = chatRoomRepository.findMyChatRooms(memberId);
        if (rooms.isEmpty()) {
            return List.of();
        }
        List<Long> roomIds = rooms.stream().map(ChatRoom::getId).toList();
        Map<Long, ChatMessage> lastByRoom = chatMessageRepository.findLatestPerRoom(roomIds).stream()
                .collect(Collectors.toMap(ChatMessage::getChatRoomId, Function.identity()));
        Map<Long, Long> unreadByRoom = chatMessageRepository.countUnreadPerRoom(roomIds, memberId).stream()
                .collect(Collectors.toMap(RoomUnreadCount::getRoomId, RoomUnreadCount::getUnreadCount));

        return rooms.stream()
                .sorted(byRecentActivityDesc(lastByRoom))
                .map(room -> ChatRoomListResponse.of(room, opponentOf(room, memberId), viewerRoleOf(room, memberId),
                        lastByRoom.get(room.getId()), unreadByRoom.getOrDefault(room.getId(), 0L)))
                .toList();
    }

    /**
     * 방의 메시지를 모두 읽음 처리한다 — 내 읽음 지점을 방의 최신 메시지 id까지 전진시킨다(참여자만 가능).
     * 메시지가 없는 방은 전진할 지점이 없어 아무것도 하지 않는다(안읽음은 어차피 0).
     * 읽음 지점 전진은 더티체킹으로 커밋된다.
     * <p>실제로 읽음 지점이 <b>전진했을 때만</b> 그 지점(최신 메시지 id)을 반환하고, 이미 그 이후를 읽은
     * 상태(재-read)이거나 빈 방이면 {@code null}을 반환한다. 컨트롤러는 이 값이 있을 때만 읽음 영수증을
     * push해, 방을 열 때마다 무의미한 영수증이 쏟아지는 것을 막는다(단조 전진 가드는 엔티티가 이미 보장).
     */
    @Transactional
    public Long markRoomAsRead(Long memberId, Long roomId) {
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        validateParticipant(room, memberId);

        Long before = room.lastReadMessageIdOf(memberId);
        Long latestMessageId = chatMessageRepository.findMaxIdByRoom(roomId);
        if (latestMessageId != null && (before == null || latestMessageId > before)) {
            room.markRead(memberId, latestMessageId);
            return latestMessageId;
        }
        return null;
    }

    /**
     * 최근 활동순 정렬(마지막 메시지 시각 DESC). 방 목록과 방별 마지막 메시지를 이미 메모리에 다 로딩했고,
     * 이 API는 페이지네이션 없이 전체 목록을 반환하며 개인 목록이라 방 수가 작아 DB 비정규화 없이 여기서 정렬한다.
     * 메시지가 없는 방은 방 생성 시각을 활동 시각으로 보아(갓 만든 빈 방이 위로) 정렬하고,
     * 동시각은 roomId DESC로 결정성을 확보한다(메시지 id는 시각과 동일 순서라 시각 하나로 충분).
     */
    private Comparator<ChatRoom> byRecentActivityDesc(Map<Long, ChatMessage> lastByRoom) {
        return Comparator
                .comparing((ChatRoom room) -> activityTimeOf(room, lastByRoom), Comparator.reverseOrder())
                .thenComparing(ChatRoom::getId, Comparator.reverseOrder());
    }

    private LocalDateTime activityTimeOf(ChatRoom room, Map<Long, ChatMessage> lastByRoom) {
        ChatMessage last = lastByRoom.get(room.getId());
        return last != null ? last.getCreatedAt() : room.getCreatedAt();
    }

    /** 방의 메시지를 최신순 커서 페이지네이션으로 조회한다. 참여자만 접근 가능. */
    @Transactional(readOnly = true)
    public ChatMessagePageResponse getMessages(Long memberId, Long roomId, Long cursor, int size) {
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        validateParticipant(room, memberId);

        int limit = clampSize(size);
        // 다음 페이지 존재 여부 판별을 위해 한 건 더 조회한다.
        List<ChatMessage> rows = chatMessageRepository.findPageByRoom(roomId, cursor, Limit.of(limit + 1));
        boolean hasNext = rows.size() > limit;
        List<ChatMessage> page = hasNext ? rows.subList(0, limit) : rows;
        Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;

        List<ChatMessageResponse> messages = page.stream().map(ChatMessageResponse::from).toList();
        return ChatMessagePageResponse.of(messages, nextCursor, hasNext);
    }

    /**
     * 메시지를 전송한다. 참여자만 가능하며, 상대가 탈퇴한 방에는 전송할 수 없다(읽기는 유지).
     * 참여자 검증을 먼저 해 비참여자에게 상대 상태를 노출하지 않는다. 상대 탈퇴 판정은
     * 상대 프록시를 한 번 로딩(getStatus)하지만 전송 시점 1회라 비용이 작다.
     */
    @Transactional
    public ChatMessageResponse sendMessage(Long memberId, Long roomId, String content) {
        ChatRoom room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        validateParticipant(room, memberId);
        if (opponentOf(room, memberId).isWithdrawn()) {
            throw new BusinessException(ErrorCode.CHAT_PARTNER_WITHDRAWN);
        }

        Member sender = entityManager.getReference(Member.class, memberId);
        ChatMessage saved = chatMessageRepository.save(ChatMessage.of(room, sender, content));
        return ChatMessageResponse.from(saved);
    }

    /**
     * 요청자가 방 참여자(구매자·판매자)인지 여부. WebSocket 토픽 구독 인가(ChatSubscribeInterceptor)용.
     * 방이 없으면 {@code false}(구독 거부).
     */
    @Transactional(readOnly = true)
    public boolean isParticipant(Long memberId, Long roomId) {
        return chatRoomRepository.findById(roomId)
                .map(room -> room.isParticipant(memberId))
                .orElse(false);
    }

    private void validateParticipant(ChatRoom room, Long memberId) {
        if (!room.isParticipant(memberId)) {
            throw new BusinessException(ErrorCode.CHAT_ACCESS_DENIED);
        }
    }

    private Member opponentOf(ChatRoom room, Long memberId) {
        return room.getBuyerId().equals(memberId) ? room.getSeller() : room.getBuyer();
    }

    /** 요청자 본인이 이 방에서 구매자인지 판매자인지. 매너온도 후기 등록 등 구매자 전용 UI 노출 여부 판단용. */
    private String viewerRoleOf(ChatRoom room, Long memberId) {
        return room.getBuyerId().equals(memberId) ? "BUYER" : "SELLER";
    }

    private int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
