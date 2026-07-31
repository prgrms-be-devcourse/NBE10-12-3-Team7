package com.dongnemarket.escrow.entity

import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class EscrowTest {
    /**
     * 상태 전이 로직은 product/buyer/seller를 쓰지 않으므로 연관은 null로 격리한다.
     *
     * Escrow.create 의 연관 3개가 nullable 이라 Kotlin 에서도 그대로 넘길 수 있다.
     * non-null 로 조였다면 여기서 컴파일 자체가 되지 않는다.
     */
    private fun inEscrow(): Escrow = Escrow.create(null, null, null, BigDecimal.valueOf(15_000L))

    // ===== confirm =====

    @Test
    fun `confirm - IN_ESCROW 거래를 확정하면 DONE으로 전이되고 closedAt이 기록된다`() {
        val escrow = inEscrow()

        escrow.confirm()

        assertThat(escrow.status).isEqualTo(EscrowStatus.DONE)
        assertThat(escrow.closedAt).isNotNull()
    }

    @Test
    fun `confirm - 이미 DONE인 거래를 재확정하면 ESCROW_NOT_IN_ESCROW 예외`() {
        val escrow = inEscrow()
        escrow.confirm()

        val ex = assertThrows<BusinessException> { escrow.confirm() }

        assertThat(ex.errorCode).isEqualTo(ErrorCode.ESCROW_NOT_IN_ESCROW)
    }

    @Test
    fun `confirm - CANCELED 거래를 확정하면 ESCROW_NOT_IN_ESCROW 예외`() {
        val escrow = inEscrow()
        escrow.cancel()

        val ex = assertThrows<BusinessException> { escrow.confirm() }

        assertThat(ex.errorCode).isEqualTo(ErrorCode.ESCROW_NOT_IN_ESCROW)
    }

    // ===== cancel =====

    @Test
    fun `cancel - IN_ESCROW 거래를 취소하면 CANCELED로 전이되고 closedAt이 기록된다`() {
        val escrow = inEscrow()

        escrow.cancel()

        assertThat(escrow.status).isEqualTo(EscrowStatus.CANCELED)
        assertThat(escrow.closedAt).isNotNull()
    }

    @Test
    fun `cancel - 이미 DONE인 거래를 취소하면 ESCROW_NOT_CANCELABLE 예외`() {
        val escrow = inEscrow()
        escrow.confirm()

        val ex = assertThrows<BusinessException> { escrow.cancel() }

        assertThat(ex.errorCode).isEqualTo(ErrorCode.ESCROW_NOT_CANCELABLE)
    }

    @Test
    fun `cancel - 이미 CANCELED인 거래를 재취소하면 ESCROW_NOT_CANCELABLE 예외`() {
        val escrow = inEscrow()
        escrow.cancel()

        val ex = assertThrows<BusinessException> { escrow.cancel() }

        assertThat(ex.errorCode).isEqualTo(ErrorCode.ESCROW_NOT_CANCELABLE)
    }
}
