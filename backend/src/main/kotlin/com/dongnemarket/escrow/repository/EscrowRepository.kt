package com.dongnemarket.escrow.repository

import com.dongnemarket.escrow.entity.Escrow
import com.dongnemarket.escrow.entity.EscrowStatus
import org.springframework.data.jpa.repository.JpaRepository

interface EscrowRepository : JpaRepository<Escrow, Long> {
    // 해당 상품에 진행 중(예치) 거래가 이미 있는지 확인한다.
    fun existsByProductIdAndStatus(
        productId: Long,
        status: EscrowStatus,
    ): Boolean
}
