package com.dongnemarket.escrow.entity

import com.dongnemarket.global.common.BaseTimeEntity
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.entity.Member
import com.dongnemarket.product.entity.Product
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 안심결제(에스크로) 거래. 상태는 IN_ESCROW → DONE 또는 CANCELED 세 개뿐이다.
 *
 * 엔티티라서 `data class` 를 쓰지 않는다 — 생성되는 equals/hashCode 가 지연 로딩 연관을
 * 건드리고 JPA 의 동일성(같은 영속성 컨텍스트의 같은 id = 같은 객체)과도 어긋난다.
 *
 * 인자 없는 생성자를 직접 쓰지 않은 이유: `kotlin-jpa`(noarg) 플러그인이 `@Entity` 에
 * 합성해준다. 합성 생성자는 synthetic 이라 Java/Kotlin 코드에서는 호출할 수 없고
 * 리플렉션(Hibernate)만 쓸 수 있다 — 원본의 `protected Escrow() {}` 보다 오히려 안전하다.
 */
@Entity
@Table(name = "escrows")
class Escrow private constructor(
    // 연관 3개가 nullable 인 것은 DB 제약과 별개다. @JoinColumn(nullable = false) 은 그대로 두었고
    // INSERT 시점에 DB 가 null 을 거부한다. 다만 메모리 위의 객체는 null 을 가질 수 있다 —
    // EscrowTest 가 상태 전이만 격리 검증하려고 Escrow.create(null, null, null, amount) 를 쓴다.
    // non-null 로 조이면 Kotlin 이 삽입한 검사에 걸려 그 테스트 6개가 런타임에 전부 깨진다.
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "product_id", nullable = false)
    val product: Product?,
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "buyer_id", nullable = false)
    val buyer: Member?,
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "seller_id", nullable = false)
    val seller: Member?,
    @field:Column(nullable = false)
    val amount: BigDecimal,
) : BaseTimeEntity() {
    // allOpen 이 @Entity 를 open 으로 만들면 프로퍼티도 open 이 된다.
    // Kotlin 은 open 프로퍼티에 private setter 를 금지하므로 protected set 을 쓴다.
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 20)
    var status: EscrowStatus = EscrowStatus.IN_ESCROW
        protected set

    @field:Column
    var closedAt: LocalDateTime? = null
        protected set

    /** 구매확정: 예치 상태에서만 DONE 으로 전이. */
    fun confirm() {
        if (status != EscrowStatus.IN_ESCROW) {
            throw BusinessException(ErrorCode.ESCROW_NOT_IN_ESCROW)
        }
        status = EscrowStatus.DONE
        closedAt = LocalDateTime.now()
    }

    /** 취소·환불: 예치 상태에서만 CANCELED 로 전이. */
    fun cancel() {
        if (status != EscrowStatus.IN_ESCROW) {
            throw BusinessException(ErrorCode.ESCROW_NOT_CANCELABLE)
        }
        status = EscrowStatus.CANCELED
        closedAt = LocalDateTime.now()
    }

    /**
     * 이 거래의 구매자 본인인지 확인(확정·취소 권한 검증용).
     *
     * 원본은 `buyer.getId().equals(memberId)` 라 buyer 가 null 이면 NPE 였다.
     * 안전 호출이 붙어 이제는 false 를 반환한다 — 영속된 거래는 buyer 가 항상 있고
     * 테스트도 buyer 가 null 인 거래로 이 메서드를 부르지 않아 현재 결과는 동일하다.
     */
    fun isBuyer(memberId: Long?): Boolean = buyer?.id == memberId

    companion object {
        /** 거래 시작: 대금을 예치한 상태(IN_ESCROW)로 생성. amount 는 상품가 스냅샷(D3). */
        @JvmStatic
        fun create(
            product: Product?,
            buyer: Member?,
            seller: Member?,
            amount: BigDecimal,
        ): Escrow = Escrow(product, buyer, seller, amount)
    }
}
