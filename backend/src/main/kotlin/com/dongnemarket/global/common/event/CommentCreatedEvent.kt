package com.dongnemarket.global.common.event

/**
 * 상품에 새 댓글이 작성될 때 발행되는 도메인 이벤트.
 *
 * notification 도메인이 수신하여 상품 소유자에게 댓글 알림을 저장한다(상품별 코얼레싱).
 * 발행은 댓글 저장 커밋 이후(`AFTER_COMMIT`) 별도 트랜잭션에서 처리되므로,
 * 알림 저장 실패가 댓글 작성을 롤백하지 않는다(best-effort).
 *
 * @property recipientId  알림 수신자 = 상품 소유자
 * @property productId    댓글이 달린 상품 id
 * @property productTitle 알림 문구 렌더용 상품 제목 스냅샷
 * @property commenterId  댓글 작성자(자기 상품에 스스로 단 댓글은 발행 측에서 스킵)
 */
data class CommentCreatedEvent(
    val recipientId: Long,
    val productId: Long,
    val productTitle: String,
    val commenterId: Long,
)
