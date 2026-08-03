package com.dongnemarket.chat.service

import com.dongnemarket.chat.dto.ChatMessagePageResponse
import com.dongnemarket.chat.dto.ChatMessageResponse
import com.dongnemarket.chat.dto.ChatRoomDetailResponse
import com.dongnemarket.chat.dto.ChatRoomListResponse
import com.dongnemarket.chat.entity.ChatMessage
import com.dongnemarket.chat.entity.ChatRoom
import com.dongnemarket.chat.repository.ChatMessageRepository
import com.dongnemarket.chat.repository.ChatRoomRepository
import com.dongnemarket.global.common.event.ChatMessageSentEvent
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.entity.Member
import com.dongnemarket.product.service.ProductService
import jakarta.persistence.EntityManager
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 채팅 도메인 서비스.
 *
 * **주의**: 클래스 레벨에 `@Transactional`을 두지 않는다. [createRoom]은 의도적으로 트랜잭션이 없어야 하고
 * (get-or-create 경쟁 복구가 [ChatRoomCreator]의 쓰기 트랜잭션 **바깥**에서 새 트랜잭션으로 일어나야 함),
 * 나머지 조회/쓰기는 메서드별로 경계를 명시한다.
 */
@Service
class ChatService(
    private val chatRoomCreator: ChatRoomCreator,
    private val chatRoomRepository: ChatRoomRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val productService: ProductService,
    private val entityManager: EntityManager,
    private val eventPublisher: ApplicationEventPublisher,
) {
    /**
     * 상품에 대한 채팅방을 get-or-create 한다(멱등). 접근 불가 상품·자기 상품은 차단.
     *
     * 쓰기는 [ChatRoomCreator](독립 트랜잭션)에 위임하고, 동시 최초 생성 경쟁으로 INSERT가 실패하면
     * 그 트랜잭션 **바깥**에서(여기서) 이긴 방을 재조회한다 — rollback-only 트랜잭션 재사용을 피한다.
     * 메서드 자체엔 트랜잭션을 걸지 않아 위임 쓰기와 복구 조회가 서로 다른 트랜잭션에서 실행된다.
     */
    fun createRoom(
        memberId: Long,
        productId: Long,
    ): ChatRoomDetailResponse {
        productService.validateAccessibleProduct(productId)
        try {
            chatRoomCreator.createIfAbsent(memberId, productId)
        } catch (race: DataIntegrityViolationException) {
            // 경쟁에서 진 INSERT는 롤백됨. 이긴 방이 이미 존재하므로 아래 조회에서 가져온다.
        }
        val room =
            chatRoomRepository
                .findDetailByProductAndBuyer(productId, memberId)
                .orElseThrow { BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND) }
        return ChatRoomDetailResponse.of(room)
    }

    /** 특정 상품에 채팅방을 연 구매자 id들. 가격 변경 알림 수신자(그 상품에 관심 있는 구매자) 조회용. */
    @Transactional(readOnly = true)
    fun findBuyerIdsForProduct(productId: Long): List<Long> = chatRoomRepository.findBuyerIdsByProduct_Id(productId)

    /** 내가 참여한 방 목록(최근 활동순 = 마지막 메시지 시각). 상품 요약·상대방·방별 마지막 메시지를 함께 담는다. */
    @Transactional(readOnly = true)
    fun getMyRooms(memberId: Long): List<ChatRoomListResponse> {
        val rooms = chatRoomRepository.findMyChatRooms(memberId)
        if (rooms.isEmpty()) {
            return emptyList()
        }
        val roomIds = rooms.map { it.id!! }
        val lastByRoom: Map<Long?, ChatMessage> = chatMessageRepository.findLatestPerRoom(roomIds).associateBy { it.chatRoomId }
        val unreadByRoom: Map<Long, Long> =
            chatMessageRepository.countUnreadPerRoom(roomIds, memberId).associate {
                it.roomId to
                    it.unreadCount
            }

        return rooms
            .sortedWith(byRecentActivityDesc(lastByRoom))
            .map { room ->
                val roomId = room.id!!
                ChatRoomListResponse.of(
                    room,
                    opponentOf(room, memberId),
                    viewerRoleOf(room, memberId),
                    lastByRoom[roomId],
                    unreadByRoom[roomId] ?: 0L,
                )
            }
    }

    /**
     * 방의 메시지를 모두 읽음 처리한다 — 내 읽음 지점을 방의 최신 메시지 id까지 전진시킨다(참여자만 가능).
     * 메시지가 없는 방은 전진할 지점이 없어 아무것도 하지 않는다(안읽음은 어차피 0).
     * 읽음 지점 전진은 더티체킹으로 커밋된다.
     *
     * 실제로 읽음 지점이 **전진했을 때만** 그 지점(최신 메시지 id)을 반환하고, 이미 그 이후를 읽은
     * 상태(재-read)이거나 빈 방이면 `null`을 반환한다. 컨트롤러는 이 값이 있을 때만 읽음 영수증을
     * push해, 방을 열 때마다 무의미한 영수증이 쏟아지는 것을 막는다(단조 전진 가드는 엔티티가 이미 보장).
     */
    @Transactional
    fun markRoomAsRead(
        memberId: Long,
        roomId: Long,
    ): Long? {
        val room =
            chatRoomRepository
                .findById(roomId)
                .orElseThrow { BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND) }
        validateParticipant(room, memberId)

        val before = room.lastReadMessageIdOf(memberId)
        val latestMessageId = chatMessageRepository.findMaxIdByRoom(roomId)
        if (latestMessageId != null && (before == null || latestMessageId > before)) {
            room.markRead(memberId, latestMessageId)
            return latestMessageId
        }
        return null
    }

    /**
     * 최근 활동순 정렬(마지막 메시지 시각 DESC). 방 목록과 방별 마지막 메시지를 이미 메모리에 다 로딩했고,
     * 이 API는 페이지네이션 없이 전체 목록을 반환하며 개인 목록이라 방 수가 작아 DB 비정규화 없이 여기서 정렬한다.
     * 메시지가 없는 방은 방 생성 시각을 활동 시각으로 보아(갓 만든 빈 방이 위로) 정렬하고,
     * 동시각은 roomId DESC로 결정성을 확보한다(메시지 id는 시각과 동일 순서라 시각 하나로 충분).
     */
    private fun byRecentActivityDesc(lastByRoom: Map<Long?, ChatMessage>): Comparator<ChatRoom> =
        compareByDescending<ChatRoom> { activityTimeOf(it, lastByRoom) }
            .thenByDescending { it.id }

    private fun activityTimeOf(
        room: ChatRoom,
        lastByRoom: Map<Long?, ChatMessage>,
    ): LocalDateTime = (lastByRoom[room.id]?.createdAt ?: room.createdAt)!!

    /** 방의 메시지를 최신순 커서 페이지네이션으로 조회한다. 참여자만 접근 가능. */
    @Transactional(readOnly = true)
    fun getMessages(
        memberId: Long,
        roomId: Long,
        cursor: Long?,
        size: Int,
    ): ChatMessagePageResponse {
        val room =
            chatRoomRepository
                .findById(roomId)
                .orElseThrow { BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND) }
        validateParticipant(room, memberId)

        val limit = clampSize(size)
        // 다음 페이지 존재 여부 판별을 위해 한 건 더 조회한다.
        val rows = chatMessageRepository.findPageByRoom(roomId, cursor, Limit.of(limit + 1))
        val hasNext = rows.size > limit
        val page = if (hasNext) rows.subList(0, limit) else rows
        val nextCursor = if (hasNext) page.last().id else null

        val messages = page.map { ChatMessageResponse.from(it) }
        return ChatMessagePageResponse.of(messages, nextCursor, hasNext)
    }

    /**
     * 메시지를 전송한다. 참여자만 가능하며, 상대가 탈퇴한 방에는 전송할 수 없다(읽기는 유지).
     * 참여자 검증을 먼저 해 비참여자에게 상대 상태를 노출하지 않는다. 상대 탈퇴 판정은
     * 상대 프록시를 한 번 로딩(getStatus)하지만 전송 시점 1회라 비용이 작다.
     */
    @Transactional
    fun sendMessage(
        memberId: Long,
        roomId: Long,
        content: String,
    ): ChatMessageResponse {
        val room =
            chatRoomRepository
                .findById(roomId)
                .orElseThrow { BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND) }
        validateParticipant(room, memberId)
        if (opponentOf(room, memberId).isWithdrawn) {
            throw BusinessException(ErrorCode.CHAT_PARTNER_WITHDRAWN)
        }

        val sender = entityManager.getReference(Member::class.java, memberId)
        val saved = chatMessageRepository.save(ChatMessage.of(room, sender, content))

        // 수신자(상대방)의 안읽음 배지를 실시간 갱신하도록 신호를 발행한다. 커밋 후(AFTER_COMMIT) 처리되므로
        // 재조회 시 이 메시지가 이미 반영돼 있다. 자기 채팅은 불가라 수신자는 항상 상대방이다(프록시 id 접근).
        eventPublisher.publishEvent(ChatMessageSentEvent(opponentOf(room, memberId).id!!))
        return ChatMessageResponse.from(saved)
    }

    /**
     * 요청자가 방 참여자(구매자·판매자)인지 여부. WebSocket 토픽 구독 인가(ChatSubscribeInterceptor)용.
     * 방이 없으면 `false`(구독 거부).
     */
    @Transactional(readOnly = true)
    fun isParticipant(
        memberId: Long,
        roomId: Long,
    ): Boolean = chatRoomRepository.findById(roomId).map { it.isParticipant(memberId) }.orElse(false)

    private fun validateParticipant(
        room: ChatRoom,
        memberId: Long,
    ) {
        if (!room.isParticipant(memberId)) {
            throw BusinessException(ErrorCode.CHAT_ACCESS_DENIED)
        }
    }

    private fun opponentOf(
        room: ChatRoom,
        memberId: Long,
    ): Member = if (room.buyerId == memberId) room.seller else room.buyer

    /** 요청자 본인이 이 방에서 구매자인지 판매자인지. 매너온도 후기 등록 등 구매자 전용 UI 노출 여부 판단용. */
    private fun viewerRoleOf(
        room: ChatRoom,
        memberId: Long,
    ): String = if (room.buyerId == memberId) "BUYER" else "SELLER"

    private fun clampSize(size: Int): Int = if (size <= 0) DEFAULT_PAGE_SIZE else minOf(size, MAX_PAGE_SIZE)

    companion object {
        private const val DEFAULT_PAGE_SIZE = 30
        private const val MAX_PAGE_SIZE = 100
    }
}
