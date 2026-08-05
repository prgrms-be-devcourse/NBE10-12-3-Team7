package com.dongnemarket.mobile.ui.region

import com.dongnemarket.mobile.domain.model.Region
import com.dongnemarket.mobile.domain.model.RegionRef

/** 서버가 저장하는 내 동네 개수 상한. `MemberLocationService` 와 같아야 한다. */
const val MAX_MY_LOCATIONS = 2

/**
 * 동네 설정 화면의 상태 전부.
 *
 * 폼 화면이라 `Loading/Success/Error` 로 갈라지지 않고 **하나의 data class** 다.
 * 사용자가 고른 동네는 목록을 다시 받는 동안에도 **계속 화면에 있어야** 하는데,
 * sealed 로 쪼개면 로딩이 시작될 때마다 그 값을 어느 가지로 옮길지 매번 고민하게 된다.
 *
 * @param selected 내가 고른 동네. **0번이 대표**다(서버가 리스트 0번을 `active = true` 로 만든다).
 * @param path 지금까지 파고든 경로. 예: `[서울특별시, 종로구]` → 화면에 `서울특별시 > 종로구` 로 찍는다.
 *   비어 있으면 최상위(시·도) 단계다.
 * @param options 현재 단계에 보여 줄 목록. 서버가 `displayName ASC` 로 정렬해 준 그대로다.
 * @param isSaving `PUT` 진행 중. 저장 중에 목록을 건드리면 보낸 것과 화면이 어긋난다.
 * @param savedRegions 저장 성공 후 서버가 돌려준 최종 목록. 화면이 이걸 보고 이전 화면으로 돌아간다.
 */
data class RegionSettingUiState(
    val selected: List<RegionRef> = emptyList(),

    val path: List<Region> = emptyList(),
    val options: List<Region> = emptyList(),

    val isLoadingMine: Boolean = true,
    val isLoadingOptions: Boolean = false,
    /** 목록 조회 실패. 재시도 버튼을 띄운다. */
    val optionsError: String? = null,

    val isSaving: Boolean = false,
    /** 저장 실패·검증 실패. 한 번 보여주고 지운다. */
    val message: String? = null,
    val savedRegions: List<RegionRef>? = null,
) {
    /** 지금 어느 단계를 보고 있는가. 0 = 시·도, 1 = 시·군·구, 2 = 읍·면·동. */
    val depth: Int get() = path.size

    /** 화면 상단에 찍을 경로 문자열. 최상위면 null(찍을 것이 없다). */
    val pathLabel: String?
        get() = path.takeIf { it.isNotEmpty() }?.joinToString(" › ") { it.displayName }

    /** 한 단계 위로 갈 수 있는가. 최상위에서는 화면을 닫는 동작이 된다. */
    val canGoUp: Boolean get() = path.isNotEmpty()

    /** 더 고를 수 있는가. 상한에 닿으면 동을 눌러도 추가되지 않는다. */
    val canSelectMore: Boolean get() = selected.size < MAX_MY_LOCATIONS

    /**
     * 저장 버튼을 누를 수 있는가.
     *
     * **0개도 막는다.** 서버는 `regionCodes` 가 비면 400 을 주는데,
     * 그보다 앞서 "동네 없음" 상태로 저장하는 것 자체가 사용자 의도로 보기 어렵다
     * (동네를 다 지웠다면 지우려던 게 아니라 바꾸려던 것이다).
     */
    val canSave: Boolean get() = selected.isNotEmpty() && !isSaving && !isLoadingMine

    /** 화면 전체를 잠글지. 저장 중에 목록을 건드리면 보낸 것과 화면이 어긋난다. */
    val isLocked: Boolean get() = isSaving

    /** 이 지역이 이미 선택돼 있는가(중복 선택 방지 — 서버도 중복이면 400 이다). */
    fun isSelected(region: Region): Boolean = selected.any { it.code == region.code }
}
