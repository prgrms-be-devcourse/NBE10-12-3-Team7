package com.dongnemarket.mobile.data.remote.dto

import com.dongnemarket.mobile.data.remote.BigDecimalSerializer
import com.dongnemarket.mobile.domain.model.TradeStatus
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/*
 * 채팅 API 의 요청/응답 JSON 을 1:1 로 옮긴 DTO 모음.
 *
 * DTO 는 "서버가 준 JSON 그 자체"이고 도메인 모델은 "앱이 쓰기 편한 형태"다.
 * 둘을 합치면 서버가 필드 이름 하나 바꿀 때 화면까지 줄줄이 고쳐야 하므로 분리하고,
 * 변환은 ChatMapper.kt 한 곳에서만 한다. **이 파일의 타입은 Data 계층 밖으로 나가지 않는다.**
 *
 * 규칙:
 *  - 필드명 = JSON 키 그대로 (서버에 naming strategy 가 없어 camelCase 원문) → @SerialName 불필요
 *  - Java primitive(long/boolean) → Kotlin non-null, 그 외 → `?` + `= null`
 *  - 시각은 전부 String (오프셋 없고 소수부 가변이라 Instant 파싱이 반드시 실패한다)
 */

/** `POST /api/chat-rooms` 요청. 필드 하나뿐 — 상대 memberId 는 서버가 상품에서 파생한다. */
@Serializable
data class ChatRoomCreateRequest(
    val productId: Long,
)

/** `POST /api/chat-rooms/{roomId}/messages` 요청. 필드 하나뿐(이미지·타입 없음). */
@Serializable
data class ChatMessageCreateRequest(
    val content: String,
)

/**
 * 채팅 상대 요약. 프로필 이미지 컬럼이 서버에 아예 없어서 두 필드뿐이다.
 * 탈퇴 회원이면 [nickname] 이 `"탈퇴한 사용자"` 고정 문구로 온다.
 */
@Serializable
data class ChatMemberSummary(
    val memberId: Long,
    val nickname: String,
)

/**
 * 방 **생성** 응답에 실려 오는 상품 정보. `description`·지역이 있다.
 *
 * [ChatProductSummary] 와 필드 구성이 달라 한 클래스로 합칠 수 없다.
 *
 * ### ⚠️ 이 클래스가 "채팅하기"를 막고 있었다 (2026-07 수정)
 * 백엔드 지역 개편으로 `region: String` 키가 사라졌는데 여기가 **non-null·기본값 없음**이라
 * `MissingFieldException` 이 나고 → `apiCall` 이 실패로 번역 → **방 생성이 통째로 실패**했다.
 * 화면에는 "알 수 없는 오류"만 뜨고 원인 단서가 남지 않는 유형이다.
 *
 * 서버(`chat/dto/ChatProductDetail.kt`)는 이 DTO의 필드를 전부 nullable 로 선언하고 있으므로
 * 여기서도 nullable + 기본값으로 맞춘다.
 */
@Serializable
data class ChatProductDetail(
    val productId: Long,
    val title: String = "",
    val description: String = "",
    @Serializable(with = BigDecimalSerializer::class)
    val price: BigDecimal = BigDecimal.ZERO,
    val tradeStatus: TradeStatus = TradeStatus.UNKNOWN,
    val regionCode: String? = null,
    val regionName: String? = null,
    val regionFullName: String? = null,
    val thumbnailUrl: String? = null,
)

/** 방 **목록** 응답에 실려 오는 상품 정보. `description`·`region` 이 없다. */
@Serializable
data class ChatProductSummary(
    val productId: Long,
    val title: String,
    @Serializable(with = BigDecimalSerializer::class)
    val price: BigDecimal,
    val tradeStatus: TradeStatus = TradeStatus.UNKNOWN,
    val thumbnailUrl: String? = null,
)

/**
 * `POST /api/chat-rooms` 응답.
 *
 * ⚠ 상대방 키가 **`seller`** 다([ChatRoomListResponse] 는 `opponent`).
 * 타입은 같은데 키가 달라서, 두 응답을 한 DTO 로 받으려 하면 조용히 null 이 된다.
 */
@Serializable
data class ChatRoomDetailResponse(
    val roomId: Long,
    val product: ChatProductDetail,
    val seller: ChatMemberSummary,
)

/**
 * `GET /api/chat-rooms` 응답 원소.
 *
 * ⚠ 상대방 키가 **`opponent`** 다(요청자 기준 상대방).
 *
 * @param createdAt 방 생성 시각. 마지막 메시지 시각이 아니다.
 * @param lastMessage 메시지가 없으면 null. 이 DTO 에는 `@JsonInclude` 가 없어
 *   **키는 남은 채 null** 로 온다(껍데기의 `data` 키와 반대다).
 */
@Serializable
data class ChatRoomListResponse(
    val roomId: Long,
    val product: ChatProductSummary,
    val opponent: ChatMemberSummary,
    val createdAt: String,
    val lastMessage: ChatMessageResponse? = null,
    val unreadCount: Long,
)

/**
 * 메시지 한 건.
 *
 * `isRead`/`readAt` 이 없다 — 서버가 읽음 지점을 DB 컬럼으로만 관리하고
 * 어떤 DTO 에도 노출하지 않으므로 '상대가 읽음' 표시는 구현 불가다.
 * 닉네임·프로필도 없어서 [senderId] 를 내 memberId 와 비교해 좌/우를 정해야 한다.
 */
@Serializable
data class ChatMessageResponse(
    val messageId: Long,
    val senderId: Long,
    val content: String,
    val createdAt: String,
)

/**
 * `GET /api/chat-rooms/{roomId}/messages` 응답.
 *
 * ⚠ 아이템 필드명이 **`messages`** 다(상품 목록 래퍼는 `items`)
 *    → 공용 제네릭 페이지 클래스로 묶지 않는 이유.
 * ⚠ [messages] 는 **최신순(id DESC)** 이다. 뒤집는 책임은 ChatMapper 에 있다.
 *
 * @param nextCursor 이 페이지에서 가장 오래된 messageId. 마지막 페이지면 null(키는 남는다).
 * @param hasNext 서버 getter 가 `isHasNext()` 라서 JSON 키는 `"hasNext"` 다(`isHasNext` 아님).
 */
@Serializable
data class ChatMessagePageResponse(
    val messages: List<ChatMessageResponse>,
    val nextCursor: Long? = null,
    val hasNext: Boolean,
)
