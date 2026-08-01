package com.dongnemarket.chat.service

import com.dongnemarket.chat.entity.ChatRoom
import com.dongnemarket.chat.repository.ChatRoomRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.member.entity.Member
import com.dongnemarket.product.entity.Product
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 채팅방 get-or-create의 **쓰기 트랜잭션**만 담당한다(ChatService에서 분리한 별도 빈).
 *
 * **왜 분리했나**: 방을 저장하다 UNIQUE(product_id, buyer_id) 경쟁에 지면 INSERT가 실패하고
 * 이 트랜잭션은 rollback-only가 된다. 같은 트랜잭션 안에서 "이긴 방"을 재조회해 반환하면 커밋 시점에
 * `UnexpectedRollbackException`(500)이 난다. 따라서 쓰기를 독립 트랜잭션 경계로 두고,
 * 경쟁 복구(재조회)는 이 경계 **바깥**(ChatService)에서 새 트랜잭션으로 수행한다.
 */
@Component
class ChatRoomCreator(
    private val chatRoomRepository: ChatRoomRepository,
    private val entityManager: EntityManager,
) {
    /**
     * (상품, 구매자) 방이 없으면 생성한다. 이미 있으면 아무 것도 하지 않는다.
     * 판매자는 상품 소유자에서 파생해 스냅샷 저장하고, 자기 상품이면 차단한다.
     * 동시 최초 생성 경쟁 시 진 쪽은 UNIQUE 위반으로 `DataIntegrityViolationException`을 던진다(호출자가 복구).
     */
    @Transactional
    fun createIfAbsent(memberId: Long, productId: Long) {
        if (chatRoomRepository.findByProduct_IdAndBuyer_Id(productId, memberId).isPresent) {
            return
        }
        val product = entityManager.find(Product::class.java, productId)
        val seller = product.member
        if (seller.id == memberId) {
            throw BusinessException(ErrorCode.CANNOT_CHAT_WITH_SELF)
        }
        val buyer = entityManager.getReference(Member::class.java, memberId)
        chatRoomRepository.save(ChatRoom.of(product, buyer, seller))
    }
}
