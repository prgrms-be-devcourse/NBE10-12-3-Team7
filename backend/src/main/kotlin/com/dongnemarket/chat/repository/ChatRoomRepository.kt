package com.dongnemarket.chat.repository

import com.dongnemarket.chat.entity.ChatRoom
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface ChatRoomRepository : JpaRepository<ChatRoom, Long> {
    /**
     * (상품, 구매자) 쌍으로 기존 방을 찾는다. get-or-create의 "get" 단계.
     * `UNIQUE(product_id, buyer_id)` 제약과 1:1로 대응하므로 결과는 최대 한 건이다.
     */
    fun findByProduct_IdAndBuyer_Id(
        productId: Long,
        buyerId: Long,
    ): Optional<ChatRoom>

    /**
     * 내가 참여한(구매자 또는 판매자) 방 목록을 조회한다.
     *
     * 참여자 필터(`buyer.id`/`seller.id`)는 chat_rooms 컬럼이라 조인 없이 처리된다
     * (seller를 방에 저장한 덕분 — 인가/필터가 순수 row 비교).
     * 목록 렌더링에 필요한 상품·상대방을 `JOIN FETCH`로 함께 로딩해 N+1을 방지한다.
     *
     * 여기서는 결정적 입력을 위해 id DESC(생성 역순)로 조회하고,
     * **최종 정렬(최근 활동순 = 마지막 메시지 시각)은 서비스에서** 마지막 메시지를 함께 로딩한 뒤
     * 메모리에서 적용한다([ChatService.getMyRooms]). 개인 목록이라 방 수가 작아 DB 비정규화는 불필요.
     */
    @Query(
        "SELECT r FROM ChatRoom r " +
            "JOIN FETCH r.product " +
            "JOIN FETCH r.buyer " +
            "JOIN FETCH r.seller " +
            "WHERE r.buyer.id = :memberId OR r.seller.id = :memberId " +
            "ORDER BY r.id DESC",
    )
    fun findMyChatRooms(
        @Param("memberId") memberId: Long,
    ): List<ChatRoom>

    /**
     * (상품, 구매자)로 방을 입장 상세와 함께 조회한다. 입장 응답(상품 상세·판매자)에 필요한 product·seller를 fetch join.
     * get-or-create 직후 반환용.
     */
    @Query(
        "SELECT r FROM ChatRoom r " +
            "JOIN FETCH r.product " +
            "LEFT JOIN FETCH r.product.regionRef " +
            "JOIN FETCH r.seller " +
            "WHERE r.product.id = :productId AND r.buyer.id = :buyerId",
    )
    fun findDetailByProductAndBuyer(
        @Param("productId") productId: Long,
        @Param("buyerId") buyerId: Long,
    ): Optional<ChatRoom>

    /**
     * 특정 상품에 채팅방을 연 구매자들의 id. 가격 변경 알림 수신자(이 상품에 관심 있는 구매자) 조회용.
     * `UNIQUE(product_id, buyer_id)`라 구매자당 방이 최대 1개이므로 결과에 중복이 없다.
     */
    @Query("SELECT r.buyer.id FROM ChatRoom r WHERE r.product.id = :productId")
    fun findBuyerIdsByProduct_Id(
        @Param("productId") productId: Long,
    ): List<Long>
}
