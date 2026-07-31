package com.dongnemarket.auction.entity

import com.dongnemarket.global.common.BaseTimeEntity
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 실시간 경매. 상태는 ONGOING → ENDED 두 개뿐이다.
 *
 * `startPrice` 는 프로퍼티가 아니라 순수 생성자 파라미터다 — 받은 값을 `currentPrice` 의
 * 초기값으로만 쓰고 이후에는 입찰가로 갱신되기 때문이다. 주 생성자 파라미터에는
 * `protected set` 을 붙일 수 없어 본문 프로퍼티로 내렸다.
 */
@Entity
@Table(name = "auctions")
class Auction private constructor(
    @field:Column(name = "seller_id", nullable = false)
    val sellerId: Long,
    @field:Column(nullable = false, length = 100)
    val title: String,
    // imageUrl·description 은 @Column 에 nullable = false 가 없고 테스트도 null 을 넘긴다.
    @field:Column(name = "image_url")
    val imageUrl: String?,
    @field:Column(columnDefinition = "TEXT")
    val description: String?,
    startPrice: BigDecimal,
    @field:Column(nullable = false)
    val endAt: LocalDateTime,
) : BaseTimeEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    /** 시작가로 초기화, 입찰마다 최고가로 갱신 */
    @field:Column(nullable = false)
    var currentPrice: BigDecimal = startPrice
        protected set

    /** 최고가 입찰자 memberId (아직 없으면 null) = 낙찰자 후보 */
    @field:Column(name = "highest_bidder_id")
    var highestBidderId: Long? = null
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 20)
    var status: AuctionStatus = AuctionStatus.ONGOING
        protected set

    /**
     * 낙관적 락 — 동시 입찰 경합 해결.
     *
     * Hibernate 가 UPDATE 마다 값을 올리고 `WHERE version = ?` 로 선점 여부를 검사한다.
     * 우리는 읽기만 하므로 `protected set` 이지만, Hibernate 가 써야 하므로 `val` 이 아닌 `var` 다.
     */
    @field:Version
    var version: Long? = null
        protected set

    /** 입찰: 진행 중이고 현재가보다 높을 때만 최고가·최고입찰자를 갱신한다. */
    fun placeBid(
        bidderId: Long,
        amount: BigDecimal,
    ) {
        if (status != AuctionStatus.ONGOING || LocalDateTime.now().isAfter(endAt)) {
            throw BusinessException(ErrorCode.AUCTION_NOT_ONGOING)
        }
        // BigDecimal 은 Comparable 이라 compareTo(...) <= 0 을 <= 연산자로 쓸 수 있다.
        // (== 은 스케일까지 비교하므로 크기 비교에는 쓰지 않는다 — 10.0 != 10.00)
        if (amount <= currentPrice) {
            throw BusinessException(ErrorCode.BID_TOO_LOW)
        }
        currentPrice = amount
        highestBidderId = bidderId
    }

    /** 종료: 진행 중이던 경매를 종료 상태로 전이한다(이 시점의 highestBidderId 가 낙찰자). */
    fun close() {
        if (status == AuctionStatus.ONGOING) {
            status = AuctionStatus.ENDED
        }
    }

    companion object {
        /** 경매 등록: 판매자·시작가·종료시각으로 진행 중(ONGOING) 상태 생성. */
        @JvmStatic
        fun create(
            sellerId: Long,
            title: String,
            imageUrl: String?,
            description: String?,
            startPrice: BigDecimal,
            endAt: LocalDateTime,
        ): Auction = Auction(sellerId, title, imageUrl, description, startPrice, endAt)
    }
}
