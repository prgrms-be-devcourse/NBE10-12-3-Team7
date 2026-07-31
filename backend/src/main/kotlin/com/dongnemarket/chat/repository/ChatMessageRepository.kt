package com.dongnemarket.chat.repository

import com.dongnemarket.chat.entity.ChatMessage
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ChatMessageRepository : JpaRepository<ChatMessage, Long> {
    /**
     * 방의 메시지를 최신순(id DESC)으로 커서 페이지네이션 조회한다.
     *
     * **왜 offset이 아니라 커서인가**: 메시지는 무한히 쌓이고 조회 중에도 계속 삽입된다.
     * offset은 뒤로 갈수록 느려지고, 새 메시지가 들어오면 페이지 경계가 밀려 중복/누락이 생긴다.
     *
     * **왜 created_at이 아니라 id 커서인가**: id는 IDENTITY라 삽입순으로 단조증가한다.
     * 따라서 id 하나만으로 완전한 정렬키가 되어(동시각 타이브레이크 불필요) 커서가 단순해진다.
     *
     * `cursor`가 null이면 첫 페이지(가장 최근부터), 값이 있으면 그보다 오래된(id가 작은) 메시지부터.
     * sender는 화면 표시에 필요하므로 `JOIN FETCH`로 함께 로딩한다.
     */
    @Query(
        "SELECT m FROM ChatMessage m " +
            "JOIN FETCH m.sender " +
            "WHERE m.chatRoom.id = :roomId " +
            "AND (:cursor IS NULL OR m.id < :cursor) " +
            "ORDER BY m.id DESC",
    )
    fun findPageByRoom(
        @Param("roomId") roomId: Long,
        @Param("cursor") cursor: Long?,
        limit: Limit,
    ): List<ChatMessage>

    /**
     * 여러 방의 **마지막 메시지**를 한 번에 조회한다(방 목록 미리보기용). 방별 최대 id = 최신 메시지(id 단조증가).
     * 방 하나당 최대 한 건이라 N+1 없이 목록의 마지막 메시지를 채운다.
     */
    @Query(
        "SELECT m FROM ChatMessage m " +
            "JOIN FETCH m.sender " +
            "WHERE m.id IN (SELECT MAX(m2.id) FROM ChatMessage m2 " +
            "WHERE m2.chatRoom.id IN :roomIds GROUP BY m2.chatRoom.id)",
    )
    fun findLatestPerRoom(
        @Param("roomIds") roomIds: List<Long>,
    ): List<ChatMessage>

    /** 방의 마지막(최신) 메시지 id. 메시지가 없으면 null. 읽음 처리 시 읽음 지점을 이 값까지 전진시킨다. */
    @Query("SELECT MAX(m.id) FROM ChatMessage m WHERE m.chatRoom.id = :roomId")
    fun findMaxIdByRoom(
        @Param("roomId") roomId: Long,
    ): Long?

    /**
     * 여러 방의 안읽음 메시지 수를 한 번에 센다(목록 배지용, 방 하나당 한 행 → N+1 없음).
     *
     * 안읽음 = 내가 보내지 않았고(`sender ≠ 나`) 내 읽음 지점보다 뒤(`id > last_read`)인 메시지.
     * 읽음 지점은 참여 좌석에 따라 다르므로 `CASE`로 구매자/판매자 컬럼을 분기하고,
     * 아직 안 읽었으면(null) `COALESCE(.., 0)`으로 id가 1부터라 상대 메시지 전부를 안읽음으로 센다.
     * 안읽음이 0인 방은 결과 행이 없으므로 서비스에서 기본값 0으로 채운다.
     */
    @Query(
        "SELECT r.id AS roomId, COUNT(m.id) AS unreadCount " +
            "FROM ChatMessage m JOIN m.chatRoom r " +
            "WHERE r.id IN :roomIds " +
            "AND m.sender.id <> :memberId " +
            "AND m.id > CASE WHEN r.buyer.id = :memberId " +
            "THEN COALESCE(r.buyerLastReadMessageId, 0) ELSE COALESCE(r.sellerLastReadMessageId, 0) END " +
            "GROUP BY r.id",
    )
    fun countUnreadPerRoom(
        @Param("roomIds") roomIds: List<Long>,
        @Param("memberId") memberId: Long,
    ): List<RoomUnreadCount>
}
