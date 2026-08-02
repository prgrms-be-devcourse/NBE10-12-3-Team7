package com.dongnemarket.mobile.domain.model

/**
 * 내가 설정한 "내 동네" 한 건. `GET/PUT /api/members/me/locations` 의 원소다.
 *
 * 알아야 할 규칙 세 가지:
 *  - **식별자는 [RegionRef.code] 다.** 2026-07 개편 전에는 이름 문자열이 식별자였지만,
 *    지금은 코드로 통신한다. 대표 동네를 바꿀 때 서버로 보내는 것도 코드다.
 *  - 화면에 찍을 때는 [RegionRef.display](짧은 이름, 비면 전체 이름)를 쓴다.
 *  - [active] 는 클라이언트가 정하지 못한다. `PUT` 으로 보낸 **리스트의 0번 원소**를
 *    서버가 `sortOrder = 0, active = true` 로 만든다. 대표 동네를 바꾸려면 순서를 바꿔 전체를 다시 보낸다.
 */
data class MemberLocation(
    /** 지역 참조(코드·짧은이름·전체이름). 코드가 이 모델의 식별자다. */
    val region: RegionRef,
    /** 0-base 우선순위. 0번이 대표 동네다. */
    val sortOrder: Int,
    /** 대표 동네 여부(= `sortOrder == 0`). 홈 헤더에 찍는 동네가 이것이다. */
    val active: Boolean,
)
