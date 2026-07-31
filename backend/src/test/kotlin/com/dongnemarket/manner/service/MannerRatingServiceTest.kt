package com.dongnemarket.manner.service

import com.dongnemarket.chat.entity.ChatRoom
import com.dongnemarket.chat.repository.ChatRoomRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.manner.dto.MannerRatingCreateRequest
import com.dongnemarket.manner.entity.MannerRating
import com.dongnemarket.manner.repository.MannerRatingRepository
import com.dongnemarket.member.entity.Member
import com.dongnemarket.member.repository.MemberRepository
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.entity.TradeStatus
import com.dongnemarket.product.repository.ProductRepository
import com.dongnemarket.region.entity.Region
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.test.util.ReflectionTestUtils
import java.math.BigDecimal
import java.util.Optional

/** [단위] MannerRatingService — 별점 등록 검증(거래완료·참여자·중복) + 매너온도 반영 위임. */
@ExtendWith(MockitoExtension::class)
class MannerRatingServiceTest {
    @Mock
    lateinit var mannerRatingRepository: MannerRatingRepository

    @Mock
    lateinit var chatRoomRepository: ChatRoomRepository

    @Mock
    lateinit var productRepository: ProductRepository

    @Mock
    lateinit var memberRepository: MemberRepository

    @Mock
    lateinit var mannerScoreService: MannerScoreService

    @InjectMocks
    lateinit var mannerRatingService: MannerRatingService

    private fun member(id: Long): Member {
        val member = Member.createUser("member-$id@example.com", "pw", "회원$id")
        ReflectionTestUtils.setField(member, "id", id)
        return member
    }

    private fun product(
        id: Long,
        seller: Member,
        tradeStatus: TradeStatus,
    ): Product {
        val product = Product.create(seller, null, "상품", "설명", BigDecimal.valueOf(10000), yeoksam())
        ReflectionTestUtils.setField(product, "id", id)
        product.changeTradeStatus(tradeStatus)
        return product
    }

    private fun yeoksam(): Region {
        val seoul = Region.root("1100000000", "서울특별시", "서울특별시")
        val gangnam = Region.child("1168000000", 2, seoul, "서울특별시 강남구", "강남구")
        return Region.child("1168010100", 3, gangnam, "서울특별시 강남구 역삼동", "역삼동")
    }

    private fun request(
        productId: Long = 10L,
        score: Int = 5,
    ) = MannerRatingCreateRequest(productId, score)

    @Nested
    @DisplayName("성공 케이스")
    inner class Success {
        @Test
        fun `완료된 거래의 참여자가 처음 별점을 남기면 등록되고 매너온도에 반영된다`() {
            val seller = member(2L)
            val rater = member(1L)
            val product = product(10L, seller, TradeStatus.COMPLETED)
            given(productRepository.findById(10L)).willReturn(Optional.of(product))
            given(chatRoomRepository.findByProduct_IdAndBuyer_Id(10L, 1L)).willReturn(Optional.of(mock(ChatRoom::class.java)))
            given(mannerRatingRepository.existsByProduct_IdAndRater_Id(10L, 1L)).willReturn(false)
            given(memberRepository.findById(1L)).willReturn(Optional.of(rater))
            given(mannerRatingRepository.save(any(MannerRating::class.java))).willAnswer { it.arguments[0] }

            val response = mannerRatingService.rate(1L, request(score = 4))

            assertThat(response.score).isEqualTo(4)
            assertThat(response.rateeId).isEqualTo(2L)
            verify(mannerScoreService).applyRating(2L, 4)
        }
    }

    @Nested
    @DisplayName("실패 케이스")
    inner class Failure {
        @Test
        fun `대상 상품이 없으면 PRODUCT_NOT_FOUND 예외가 발생한다`() {
            given(productRepository.findById(10L)).willReturn(Optional.empty())

            val ex = assertThrows<BusinessException> { mannerRatingService.rate(1L, request()) }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND)
        }

        @Test
        fun `거래가 완료되지 않았으면 MANNER_RATING_TRADE_NOT_COMPLETED 예외가 발생한다`() {
            val product = product(10L, member(2L), TradeStatus.ON_SALE)
            given(productRepository.findById(10L)).willReturn(Optional.of(product))

            val ex = assertThrows<BusinessException> { mannerRatingService.rate(1L, request()) }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.MANNER_RATING_TRADE_NOT_COMPLETED)
        }

        @Test
        fun `그 거래의 채팅방 참여자가 아니면 MANNER_RATING_NOT_A_PARTICIPANT 예외가 발생한다`() {
            val product = product(10L, member(2L), TradeStatus.COMPLETED)
            given(productRepository.findById(10L)).willReturn(Optional.of(product))
            given(chatRoomRepository.findByProduct_IdAndBuyer_Id(10L, 1L)).willReturn(Optional.empty())

            val ex = assertThrows<BusinessException> { mannerRatingService.rate(1L, request()) }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.MANNER_RATING_NOT_A_PARTICIPANT)
        }

        @Test
        fun `이미 별점을 남긴 거래면 MANNER_RATING_ALREADY_EXISTS 예외가 발생한다`() {
            val product = product(10L, member(2L), TradeStatus.COMPLETED)
            given(productRepository.findById(10L)).willReturn(Optional.of(product))
            given(chatRoomRepository.findByProduct_IdAndBuyer_Id(10L, 1L)).willReturn(Optional.of(mock(ChatRoom::class.java)))
            given(mannerRatingRepository.existsByProduct_IdAndRater_Id(10L, 1L)).willReturn(true)

            val ex = assertThrows<BusinessException> { mannerRatingService.rate(1L, request()) }

            assertThat(ex.errorCode).isEqualTo(ErrorCode.MANNER_RATING_ALREADY_EXISTS)
        }
    }
}
