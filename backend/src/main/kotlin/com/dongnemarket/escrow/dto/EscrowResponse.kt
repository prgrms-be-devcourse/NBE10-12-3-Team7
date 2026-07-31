package com.dongnemarket.escrow.dto

import com.dongnemarket.escrow.entity.Escrow
import com.dongnemarket.escrow.entity.EscrowStatus
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 거래 응답.
 *
 * id 계열이 nullable 인 이유는 두 가지다 — 엔티티 id 는 저장 전 null 이고,
 * Escrow 의 연관 3개도 nullable 이라 `escrow.product?.id` 가 null 일 수 있다.
 * `createdAt` 은 BaseTimeEntity 가 `LocalDateTime?` 라 컴파일러가 nullable 을 강제한다.
 */
@ConsistentCopyVisibility
data class EscrowResponse private constructor(
    val escrowId: Long?,
    val productId: Long?,
    val buyerId: Long?,
    val sellerId: Long?,
    val amount: BigDecimal,
    val status: EscrowStatus,
    val createdAt: LocalDateTime?,
    val closedAt: LocalDateTime?,
) {
    companion object {
        @JvmStatic
        fun from(escrow: Escrow): EscrowResponse =
            EscrowResponse(
                escrow.id,
                // 원본은 escrow.getProduct().getId() 라 연관이 null 이면 NPE 였다.
                // 안전 호출로 바뀌었지만 이 팩토리는 영속된 거래에만 쓰이므로 결과는 동일하다.
                escrow.product?.id,
                escrow.buyer?.id,
                escrow.seller?.id,
                escrow.amount,
                escrow.status,
                escrow.createdAt,
                escrow.closedAt,
            )
    }
}
