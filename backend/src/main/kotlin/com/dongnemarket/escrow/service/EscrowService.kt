package com.dongnemarket.escrow.service

import com.dongnemarket.escrow.dto.EscrowCreateRequest
import com.dongnemarket.escrow.dto.EscrowResponse
import com.dongnemarket.escrow.entity.Escrow
import com.dongnemarket.escrow.entity.EscrowStatus
import com.dongnemarket.escrow.repository.EscrowRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.TradeStatus
import com.dongnemarket.product.repository.ProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class EscrowService(
    private val escrowRepository: EscrowRepository,
    private val productRepository: ProductRepository,
    private val memberRepository: MemberRepository,
) {
    /** 거래 시작: 검증 → 상품가 스냅샷으로 예치(IN_ESCROW) → 상품을 거래중(RESERVED)으로. */
    @Transactional
    fun create(
        buyerId: Long,
        request: EscrowCreateRequest,
    ): EscrowResponse {
        // 컨트롤러의 @Valid + @NotNull 이 걸러내므로 여기 도달하면 값이 있다.
        // (원본도 null 이 오면 Spring Data 가 "id must not be null" 로 터졌다.)
        val productId = requireNotNull(request.productId) { "productId 는 필수입니다." }

        val product =
            productRepository
                .findById(productId)
                .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }
        if (product.isDeleted) {
            throw BusinessException(ErrorCode.DELETED_PRODUCT)
        }
        if (product.isHidden) {
            throw BusinessException(ErrorCode.HIDDEN_PRODUCT)
        }

        val seller = product.member
        if (seller.id == buyerId) {
            throw BusinessException(ErrorCode.CANNOT_ESCROW_OWN_PRODUCT)
        }
        if (product.tradeStatus != TradeStatus.ON_SALE) {
            throw BusinessException(ErrorCode.PRODUCT_NOT_ON_SALE)
        }
        if (escrowRepository.existsByProductIdAndStatus(product.id, EscrowStatus.IN_ESCROW)) {
            throw BusinessException(ErrorCode.ESCROW_ALREADY_EXISTS)
        }

        val buyer =
            memberRepository
                .findById(buyerId)
                .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }

        val saved = escrowRepository.save(Escrow.create(product, buyer, seller, product.price))
        product.changeTradeStatus(TradeStatus.RESERVED)

        return EscrowResponse.from(saved)
    }

    /** 거래 조회. */
    fun get(escrowId: Long): EscrowResponse {
        val escrow =
            escrowRepository
                .findById(escrowId)
                .orElseThrow { BusinessException(ErrorCode.ESCROW_NOT_FOUND) }
        return EscrowResponse.from(escrow)
    }

    /** 구매확정: 본인 거래 검증 → DONE 전이 → 상품 거래완료(COMPLETED). */
    @Transactional
    fun confirm(
        buyerId: Long,
        escrowId: Long,
    ): EscrowResponse {
        val escrow = findOwnedEscrow(buyerId, escrowId)
        escrow.confirm()
        productOf(escrow).complete()
        return EscrowResponse.from(escrow)
    }

    /** 취소·환불: 본인 거래 검증 → CANCELED 전이 → 상품 판매중(ON_SALE) 복귀. */
    @Transactional
    fun cancel(
        buyerId: Long,
        escrowId: Long,
    ): EscrowResponse {
        val escrow = findOwnedEscrow(buyerId, escrowId)
        escrow.cancel()
        productOf(escrow).changeTradeStatus(TradeStatus.ON_SALE)
        return EscrowResponse.from(escrow)
    }

    /** 거래를 찾고, 요청자가 이 거래의 구매자 본인인지 검증한다. */
    private fun findOwnedEscrow(
        buyerId: Long,
        escrowId: Long,
    ): Escrow {
        val escrow =
            escrowRepository
                .findById(escrowId)
                .orElseThrow { BusinessException(ErrorCode.ESCROW_NOT_FOUND) }
        if (!escrow.isBuyer(buyerId)) {
            throw BusinessException(ErrorCode.ESCROW_ACCESS_DENIED)
        }
        return escrow
    }

    /**
     * Escrow.product 는 nullable 이지만(테스트가 연관 없이 상태 전이만 검증한다),
     * 리포지토리에서 꺼낸 거래는 DB 제약상 상품을 반드시 가진다. 그 불변식을 여기서 한 번만 확인한다.
     */
    private fun productOf(escrow: Escrow): Product = checkNotNull(escrow.product) { "영속된 Escrow 는 product 를 반드시 가진다." }
}
