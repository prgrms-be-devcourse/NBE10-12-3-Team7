package com.dongnemarket.notification.service;

import com.dongnemarket.chat.dto.ChatRoomListResponse;
import com.dongnemarket.chat.service.ChatService;
import com.dongnemarket.favorite.service.FavoriteService;
import com.dongnemarket.member.entity.Member;
import com.dongnemarket.notification.dto.NotificationResponse;
import com.dongnemarket.notification.entity.Notification;
import com.dongnemarket.notification.entity.NotificationType;
import com.dongnemarket.notification.repository.NotificationRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
@Transactional(readOnly = true)
public class NotificationService {

    /** 알림 목록 상한. 개인 목록이지만 읽은 알림이 누적되므로 최근순으로 캡한다. */
    private static final Limit MY_NOTIFICATIONS_LIMIT = Limit.of(100);

    private final NotificationRepository notificationRepository;
    private final ChatService chatService;
    private final FavoriteService favoriteService;
    private final EntityManager entityManager;
    private final UnreadBadgePublisher badgePublisher;

    public NotificationService(NotificationRepository notificationRepository,
                               ChatService chatService,
                               FavoriteService favoriteService,
                               EntityManager entityManager,
                               UnreadBadgePublisher badgePublisher) {
        this.notificationRepository = notificationRepository;
        this.chatService = chatService;
        this.favoriteService = favoriteService;
        this.entityManager = entityManager;
        this.badgePublisher = badgePublisher;
    }

    /**
     * 댓글 알림을 저장한다. 같은 상품의 안읽은 알림이 이미 있으면 새 row 대신 발생 시각만 갱신(코얼레싱)한다.
     * <p>동시 이벤트 레이스로 안읽은 알림이 2행 이상이면, 최신 1개만 갱신하고 나머지는 삭제해 1행으로 수렴시킨다
     * (DB 부분 유니크로 못 막는 대신 애플리케이션이 수렴). 상품 삭제·개명에도 안정적이도록 문구는 스냅샷으로 저장한다.
     */
    @Transactional
    public void notifyComment(Long recipientId, Long productId, String productTitle) {
        coalesceOrSave(recipientId, productId, NotificationType.COMMENT, buildCommentMessage(productTitle));
        badgePublisher.pushTo(recipientId);
    }

    /**
     * 상품 가격 변경 알림을 저장한다. 그 상품에 <b>채팅방을 연 구매자</b>와 <b>관심 등록한 사용자</b>의 합집합에게
     * 각각 상품별 코얼레싱으로 저장한다. 두 집합에 모두 속한 사용자는 {@link LinkedHashSet}으로 한 번만 발송한다
     * (설령 중복 발송돼도 코얼레싱이 안읽은 알림을 1행으로 수렴시키므로, Set은 불필요한 renotify를 아끼는 최적화다).
     * <p>판매자 본인은 두 집합 모두에서 제외된다: 채팅은 자기 상품 채팅 불가(CANNOT_CHAT_WITH_SELF),
     * 관심은 쿼리에서 판매자 id를 제외({@code findFavoriteMemberIdsForProduct})한다.
     */
    @Transactional
    public void notifyPriceChange(Long productId, String productTitle) {
        String message = buildPriceChangeMessage(productTitle);
        Set<Long> recipientIds = new LinkedHashSet<>(chatService.findBuyerIdsForProduct(productId));
        recipientIds.addAll(favoriteService.findFavoriteMemberIdsForProduct(productId));
        for (Long recipientId : recipientIds) {
            coalesceOrSave(recipientId, productId, NotificationType.PRICE_CHANGE, message);
            badgePublisher.pushTo(recipientId);
        }
    }

    /**
     * 수신자·상품·타입 단위로 저장하되, 안읽은 알림이 이미 있으면 새 row 대신 발생 시각만 갱신(코얼레싱)한다.
     * 동시 이벤트 레이스로 안읽은 알림이 2행 이상이면 최신 1개만 갱신하고 나머지는 삭제해 1행으로 수렴시킨다
     * (DB 부분 유니크로 못 막는 대신 애플리케이션이 수렴). 문구는 상품 삭제·개명에도 안정적이도록 스냅샷으로 저장한다.
     */
    private void coalesceOrSave(Long recipientId, Long productId, NotificationType type, String message) {
        List<Notification> unread = notificationRepository
                .findByRecipient_IdAndProductIdAndTypeAndIsReadFalseOrderByLastNotifiedAtDesc(
                        recipientId, productId, type);

        if (unread.isEmpty()) {
            Member recipient = entityManager.getReference(Member.class, recipientId);
            notificationRepository.save(Notification.of(recipient, type, message, productId));
            return;
        }

        // 최신 1개만 남겨 발생 시각을 끌어올리고(코얼레싱), 레이스로 중복된 나머지는 정리해 1행으로 수렴시킨다.
        unread.get(0).renotify();
        if (unread.size() > 1) {
            notificationRepository.deleteAll(unread.subList(1, unread.size()));
        }
    }

    /**
     * 내 알림 피드를 최근 발생순으로 조회한다.
     * <p>저장형 <b>댓글 알림</b>과, 저장하지 않고 안읽은 채팅방에서 파생한 <b>채팅 알림</b>(방마다 1건)을 합쳐
     * {@code occurredAt} DESC로 정렬한다. 채팅은 방 입장(읽음 처리) 시 안읽음이 0이 되어 자동으로 사라진다.
     */
    public List<NotificationResponse> getMyNotifications(Long memberId) {
        Stream<NotificationResponse> comments = notificationRepository
                .findByRecipient_IdOrderByLastNotifiedAtDesc(memberId, MY_NOTIFICATIONS_LIMIT)
                .stream()
                .map(NotificationResponse::from);
        Stream<NotificationResponse> chats = unreadRooms(memberId)
                .map(this::toChatNotification);
        return Stream.concat(comments, chats)
                .sorted(Comparator.comparing(NotificationResponse::getOccurredAt, Comparator.reverseOrder()))
                .toList();
    }

    /** 안읽은 알림 총 개수(안읽은 댓글 알림 + 안읽은 채팅방 수). 헤더 배지용. */
    public long getUnreadCount(Long memberId) {
        long unreadComments = notificationRepository.countByRecipient_IdAndIsReadFalse(memberId);
        long unreadRooms = unreadRooms(memberId).count();
        return unreadComments + unreadRooms;
    }

    /** 내 안읽은 알림을 전부 읽음 처리한다(알림 패널 열람 시). 채팅 알림은 방 읽음 처리로 사라지므로 여기서 건드리지 않는다. */
    @Transactional
    public void markAllRead(Long memberId) {
        notificationRepository.markAllReadByRecipientId(memberId);
    }

    /** 내가 참여한 방 중 안읽은 메시지가 있는 방(채팅 알림의 원천). 상대·상품·마지막 메시지를 그대로 재사용한다. */
    private Stream<ChatRoomListResponse> unreadRooms(Long memberId) {
        return chatService.getMyRooms(memberId).stream()
                .filter(room -> room.getUnreadCount() > 0);
    }

    private NotificationResponse toChatNotification(ChatRoomListResponse room) {
        LocalDateTime occurredAt = room.getLastMessage() != null
                ? room.getLastMessage().getCreatedAt()
                : room.getCreatedAt();
        String message = buildChatMessage(room.getProduct().getTitle());
        return NotificationResponse.chat(message, room.getProduct().getProductId(), room.getRoomId(), occurredAt);
    }

    private String buildCommentMessage(String productTitle) {
        return "💬 \"" + productTitle + "\" 글에 새로운 댓글이 작성되었습니다.";
    }

    private String buildPriceChangeMessage(String productTitle) {
        // 가격 수치를 문구에 넣지 않는다: 코얼레싱 시 renotify()가 message를 갱신하지 않아 옛 가격이 남기 때문.
        return "🏷️ \"" + productTitle + "\"의 가격이 변경되었습니다.";
    }

    private String buildChatMessage(String productTitle) {
        // 상대 닉네임을 넣지 않는다: 채팅 알림은 조회 시점 파생이라, 상대가 나중에 탈퇴하면
        // 과거 문구가 "탈퇴한 사용자"로 소급 표시되는 드리프트가 생기기 때문(상품명만으로 안내).
        return "🔔 \"" + productTitle + "\"에 새로운 채팅이 도착했습니다!";
    }
}
