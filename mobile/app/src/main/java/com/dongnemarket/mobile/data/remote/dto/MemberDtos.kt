package com.dongnemarket.mobile.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * `GET /api/members/me` 응답의 `data`. 백엔드 원본: `member/dto/MemberResponse`.
 */
@Serializable
data class MemberResponseDto(
    /** JWT 의 `sub` 와 같은 값. 채팅에서 내 메시지를 판별하는 기준이 된다. */
    val memberId: Long,
    val email: String,
    val nickname: String,
    /**
     * `"ROLE_USER"` / `"ROLE_ADMIN"`.
     *
     * 왜 Kotlin enum 이 아니라 String 인가: 백엔드에 권한이 하나 추가되면
     * enum 파싱이 실패해 내 정보 조회 전체가 깨진다(= 세션 확인이 죽는다).
     * 문자열로 받아 두고 매퍼에서 `MemberRole.UNKNOWN` 으로 강등시키는 쪽이 안전하다.
     */
    val role: String? = null,
    /** `"ACTIVE"` / `"SUSPENDED"` / `"DELETED"`. role 과 같은 이유로 String 이다. */
    val status: String? = null,
    /**
     * 오프셋(Z) 없고 소수부 자릿수가 가변인 ISO local 문자열. `Instant`/`OffsetDateTime` 파싱은 반드시 실패한다.
     * 기본값 `""` 를 둔 이유는 서버가 이 키를 빼더라도 파싱 자체가 깨지지 않게 하기 위한 방어다.
     */
    val createdAt: String = "",
)

/**
 * `GET/PUT /api/members/me/locations` 응답 원소. 백엔드 원본: `member/dto/MemberLocationResponse.java`.
 *
 * ### ⚠️ 2026-07 필드 교체
 * 이전 `region: String` 하나가 **`regionCode`·`regionName`·`regionFullName` 셋으로 쪼개졌다.**
 * 옛 `region` 키는 서버에 없으므로 그대로 두면 **파싱 예외로 내 동네 조회 전체가 실패**한다.
 *
 * 셋 다 기본값을 준 이유: 키가 빠져도 목록이 통째로 죽지 않게 하는 방어.
 * `active` 는 Java 의 `isActive()` getter 라서 JSON 키가 `"active"` 다(`isActive` 아님).
 */
@Serializable
data class MemberLocationResponseDto(
    val regionCode: String = "",
    val regionName: String = "",
    val regionFullName: String = "",
    val sortOrder: Int = 0,
    val active: Boolean = false,
)

/**
 * `PUT /api/members/me/locations` 요청 본문. 백엔드 원본: `member/dto/MemberLocationUpdateRequest.java`.
 *
 * ### ⚠️ 2026-07 필드명 교체 — `regions` → `regionCodes`
 * 서버는 이제 **지역 코드**를 받는다(`GET /api/regions` 의 `code`).
 * 이름 문자열을 보내면 `@NotEmpty` 위반으로 **400** 이다 — 서버가 `regions` 키를 읽지 않기 때문에
 * `regionCodes` 가 비어 있는 것으로 판정된다.
 *
 * 1~2개만 허용(`@Size(max=2)`)되고, **리스트 0번이 대표 동네**가 된다.
 */
@Serializable
data class MemberLocationUpdateRequestDto(
    val regionCodes: List<String>,
)
