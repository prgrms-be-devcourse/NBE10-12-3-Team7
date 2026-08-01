package com.dongnemarket.manner.service

import com.dongnemarket.chat.repository.ChatRoomRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.manner.dto.MannerRatingCreateRequest
import com.dongnemarket.manner.dto.MannerRatingResponse
import com.dongnemarket.manner.entity.MannerRating
import com.dongnemarket.manner.repository.MannerRatingRepository
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.TradeStatus
import com.dongnemarket.product.repository.ProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 거래 후 별점(후기) 등록. "그 거래의 채팅방 참여자(구매자)만, 거래가 실제로 완료된 뒤에만,
 * 한 번만" 별점을 남길 수 있도록 검증한다 — 익명·중복·엉뚱한 상대 평가를 막는 어뷰징 방지 장치다.
 */
@Service
@Transactional(readOnly = true)
class MannerRatingService(
    private val mannerRatingRepository: MannerRatingRepository,
    private val chatRoomRepository: ChatRoomRepository,
    private val productRepository: ProductRepository,
    private val memberRepository: MemberRepository,
    private val mannerScoreService: MannerScoreService,
) {
    @Transactional
    fun rate(
        raterId: Long,
        request: MannerRatingCreateRequest,
    ): MannerRatingResponse {
        // productId/score는 @Valid가 이미 non-null을 보장한 뒤에만 이 메서드에 도달한다(컨트롤러의 @Valid).
        val productId = request.productId!!
        val score = request.score!!
        val product = productRepository.findById(productId).orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }

        if (product.tradeStatus != TradeStatus.COMPLETED) {
            throw BusinessException(ErrorCode.MANNER_RATING_TRADE_NOT_COMPLETED)
        }

        // 그 상품에 대해 이 회원이 구매자로 채팅방을 연 적이 있어야 한다(= 실제 거래 상대라는 근거).
        chatRoomRepository
            .findByProduct_IdAndBuyer_Id(productId, raterId)
            .orElseThrow { BusinessException(ErrorCode.MANNER_RATING_NOT_A_PARTICIPANT) }

        if (mannerRatingRepository.existsByProduct_IdAndRater_Id(productId, raterId)) {
            throw BusinessException(ErrorCode.MANNER_RATING_ALREADY_EXISTS)
        }

        val rater = memberRepository.findById(raterId).orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }
        val ratee = product.member

        val rating = mannerRatingRepository.save(MannerRating.of(product, rater, ratee, score))

        mannerScoreService.applyRating(ratee.id, score)

        return MannerRatingResponse.from(rating)
    }
}
