package com.dongnemarket.chat.service

import com.dongnemarket.chat.entity.ChatRoom
import com.dongnemarket.chat.repository.ChatMessageRepository
import com.dongnemarket.chat.repository.ChatRoomRepository
import com.dongnemarket.member.entity.Member
import com.dongnemarket.product.entity.Product
import com.dongnemarket.product.service.ProductService
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import java.util.Optional

/**
 * ChatService 단위 테스트.
 *
 * 통합 테스트(ChatControllerTest)로 커버되는 매핑·인가·조회는 다루지 않고,
 * **통합으로 재현 불가능한 비자명 분기**인 get-or-create 동시성 경쟁 복구만 검증한다.
 */
@ExtendWith(MockitoExtension::class)
@DisplayName("ChatService 단위 테스트")
class ChatServiceTest {
    @Mock
    lateinit var chatRoomCreator: ChatRoomCreator

    @Mock
    lateinit var chatRoomRepository: ChatRoomRepository

    @Mock
    lateinit var chatMessageRepository: ChatMessageRepository

    @Mock
    lateinit var productService: ProductService

    @Mock
    lateinit var entityManager: EntityManager

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    @InjectMocks
    lateinit var chatService: ChatService

    @Test
    fun `방 생성 중 동시 경쟁으로 UNIQUE 위반이 나면, 예외를 삼키고 이긴 방을 재조회해 반환한다`() {
        val winner = stubRoom()
        // 쓰기 위임(별도 트랜잭션)에서 경쟁에 져 INSERT 실패
        willThrow(DataIntegrityViolationException("duplicate key"))
            .given(chatRoomCreator)
            .createIfAbsent(BUYER_ID, PRODUCT_ID)
        // 새 트랜잭션으로 재조회하면 이긴 방이 존재
        given(chatRoomRepository.findDetailByProductAndBuyer(PRODUCT_ID, BUYER_ID))
            .willReturn(Optional.of(winner))

        val response = chatService.createRoom(BUYER_ID, PRODUCT_ID)

        // 500(UnexpectedRollbackException) 없이 정상 응답 — 경쟁 복구가 쓰기 트랜잭션 바깥에서 일어남
        assertThat(response).isNotNull()
        verify(chatRoomRepository).findDetailByProductAndBuyer(PRODUCT_ID, BUYER_ID)
    }

    @Test
    fun `정상 생성 후 상품 상세·판매자와 함께 반환한다`() {
        val room = stubRoom()
        given(chatRoomRepository.findDetailByProductAndBuyer(PRODUCT_ID, BUYER_ID))
            .willReturn(Optional.of(room))

        val response = chatService.createRoom(BUYER_ID, PRODUCT_ID)

        assertThat(response).isNotNull()
        verify(productService).validateAccessibleProduct(PRODUCT_ID)
        verify(chatRoomCreator).createIfAbsent(BUYER_ID, PRODUCT_ID)
    }

    /** DTO 매핑에 필요한 최소 스텁만 둔 방(세부 값은 검증 대상 아님). */
    private fun stubRoom(): ChatRoom {
        val room = mock(ChatRoom::class.java)
        given(room.product).willReturn(mock(Product::class.java))
        given(room.seller).willReturn(mock(Member::class.java))
        return room
    }

    companion object {
        private const val BUYER_ID = 1L
        private const val PRODUCT_ID = 100L
    }
}
