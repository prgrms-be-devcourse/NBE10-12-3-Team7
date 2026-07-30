package com.dongnemarket.notification.repository

import com.dongnemarket.notification.entity.Notification
import com.dongnemarket.notification.entity.NotificationType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface NotificationRepository : JpaRepository<Notification, Long> {
    /**
     * 코얼레싱 대상 조회: 같은 수신자·상품·타입의 *안읽은* 알림들을 최신순으로 찾는다.
     *
     * 정상 흐름에선 0~1행이지만, 동시 이벤트 레이스로 2행 이상이 생길 수 있어 `List`로 받는다.
     * 핸들러는 비었으면 새로 저장, 있으면 최신 1개만 `renotify`하고 나머지는 정리해 1행으로 수렴시킨다.
     * (MySQL 은 `is_read=false` 부분 유니크 인덱스를 지원하지 않아 DB 제약으로 못 막고, 애플리케이션이 수렴시킨다.)
     */
    fun findByRecipient_IdAndProductIdAndTypeAndIsReadFalseOrderByLastNotifiedAtDesc(
        recipientId: Long,
        productId: Long,
        type: NotificationType,
    ): List<Notification>

    /** 내 알림 목록을 최근 발생순으로 조회한다. 읽은 알림이 누적되므로 상한을 둔다(개인 목록이라 바운드가 작다). */
    fun findByRecipient_IdOrderByLastNotifiedAtDesc(
        recipientId: Long,
        limit: Limit,
    ): List<Notification>

    /** 내 안읽은 (저장형) 알림 수. 배지의 안읽음 카운트에 채팅 파생분과 합산한다. */
    fun countByRecipient_IdAndIsReadFalse(recipientId: Long): Long

    /** 내 안읽은 알림을 한 번에 읽음 처리한다(패널 열람 = 전체 읽음). */
    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.recipient.id = :recipientId AND n.isRead = false")
    fun markAllReadByRecipientId(
        @Param("recipientId") recipientId: Long,
    ): Int
}
