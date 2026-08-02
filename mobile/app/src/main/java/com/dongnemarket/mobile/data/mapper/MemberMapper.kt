package com.dongnemarket.mobile.data.mapper

import com.dongnemarket.mobile.data.remote.dto.MemberLocationResponseDto
import com.dongnemarket.mobile.data.remote.dto.MemberResponseDto
import com.dongnemarket.mobile.domain.model.Member
import com.dongnemarket.mobile.domain.model.MemberLocation
import com.dongnemarket.mobile.domain.model.MemberRole
import com.dongnemarket.mobile.domain.model.MemberStatus
import com.dongnemarket.mobile.domain.model.RegionRef

/**
 * DTO(서버 계약) → 도메인 모델 변환. **DTO 가 UI까지 새어 나가지 않게 하는 경계선**이다.
 *
 * 여기서 변환을 하면 좋은 점: 서버가 필드명을 바꾸거나 값 표현을 바꿔도
 * 고칠 곳이 이 파일 하나이고, ViewModel·화면은 그대로 둘 수 있다.
 */

/** `MemberResponse` → [Member]. role/status 문자열을 도메인 enum 으로 좁힌다. */
fun MemberResponseDto.toDomain(): Member = Member(
    memberId = memberId,
    email = email,
    nickname = nickname,
    role = role.toMemberRole(),
    status = status.toMemberStatus(),
    createdAt = createdAt,
)

/**
 * `MemberLocationResponse` → [MemberLocation].
 *
 * ⚠️ 2026-07 개편으로 서버가 `region` 하나 대신 **3필드**를 준다.
 * 여기서 [RegionRef] 로 묶어 도메인에 넘긴다 — 화면은 `location.region.display` 만 보면 된다.
 */
fun MemberLocationResponseDto.toDomain(): MemberLocation = MemberLocation(
    region = RegionRef(
        code = regionCode,
        name = regionName,
        fullName = regionFullName,
    ),
    sortOrder = sortOrder,
    active = active,
)

/**
 * 서버 문자열 → [MemberRole].
 *
 * `when` 의 `else` 분기(= UNKNOWN 폴백)가 이 함수의 존재 이유다.
 * `MemberRole.valueOf(this)` 로 썼다면 백엔드에 권한이 하나 추가되는 순간
 * `IllegalArgumentException` 이 터져 **내 정보 조회 = 세션 확인 자체가 죽는다.**
 * 권한은 화면 분기용 부가정보일 뿐이므로, 모르는 값은 조용히 UNKNOWN 으로 강등시키고 앱은 계속 돈다.
 *
 * `ROLE_` 접두를 떼는 것은 Spring Security 규약(`ROLE_` prefix)을 도메인까지 끌고 오지 않기 위함이다.
 */
private fun String?.toMemberRole(): MemberRole = when (this?.trim()?.uppercase()) {
    "ROLE_USER", "USER" -> MemberRole.USER
    "ROLE_ADMIN", "ADMIN" -> MemberRole.ADMIN
    else -> MemberRole.UNKNOWN
}

/** 서버 문자열 → [MemberStatus]. [toMemberRole] 과 같은 이유로 폴백이 필수다. */
private fun String?.toMemberStatus(): MemberStatus = when (this?.trim()?.uppercase()) {
    "ACTIVE" -> MemberStatus.ACTIVE
    "SUSPENDED" -> MemberStatus.SUSPENDED
    "DELETED" -> MemberStatus.DELETED
    else -> MemberStatus.UNKNOWN
}
