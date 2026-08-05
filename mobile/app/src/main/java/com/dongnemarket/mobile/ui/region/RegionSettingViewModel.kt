package com.dongnemarket.mobile.ui.region

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dongnemarket.mobile.domain.model.AppError
import com.dongnemarket.mobile.domain.model.Region
import com.dongnemarket.mobile.domain.repository.MemberRepository
import com.dongnemarket.mobile.domain.repository.RegionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 동네 설정 화면의 상태 보유자.
 *
 * ## 이 화면이 하는 일 두 가지
 *  1. **탐색** — 시·도 → 시·군·구 → 읍·면·동을 한 단계씩 내려간다([regionRepository]).
 *     서버가 계층 전체를 주는 API 를 두지 않아 드릴다운이 유일한 방법이다.
 *  2. **저장** — 고른 동네를 `PUT /api/members/me/locations` 로 보낸다([memberRepository]).
 *
 * ## 반드시 지켜야 하는 서버 규칙
 *  - **전체 교체**다. 부분 추가·단건 삭제 API 가 없어서 항상 최종 목록 전체를 보낸다.
 *  - **리스트 0번이 대표 동네**가 된다(서버가 `sortOrder = 0, active = true` 로 만든다).
 *    대표만 바꾸는 API 는 없다 → 순서를 바꿔 전체를 다시 보낸다.
 *  - **1~2개**, 중복 불가, **읍·면·동(level 3)만**.
 */
@HiltViewModel
class RegionSettingViewModel @Inject constructor(
    private val regionRepository: RegionRepository,
    private val memberRepository: MemberRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RegionSettingUiState())
    val uiState: StateFlow<RegionSettingUiState> = _uiState.asStateFlow()

    /**
     * 목록 조회 작업. 새 요청이 들어오면 이전 요청을 취소한다.
     *
     * 취소하지 않으면 사용자가 빠르게 여러 단계를 오갈 때 **먼저 시작한 느린 응답이
     * 나중에 도착해 현재 화면을 덮는다** — 강남구를 눌렀는데 종로구 목록이 뜨는 종류의 버그다.
     */
    private var optionsJob: Job? = null

    init {
        loadMyLocations()
        loadOptions(parent = null)
    }

    // ──────────────────────── 탐색 ────────────────────────

    /**
     * 목록의 항목을 눌렀다.
     *
     * 읍·면·동이면 **선택**, 그 위 단계면 **한 단계 파고든다**.
     * 이 분기를 화면이 아니라 여기서 하는 이유: "무엇을 고를 수 있는가" 는 서버 규칙이고,
     * 화면이 그 규칙을 알면 규칙이 두 곳에 흩어진다.
     */
    fun onRegionClick(region: Region) {
        if (uiState.value.isLocked) return
        if (region.isSelectable) toggleSelection(region) else drillInto(region)
    }

    private fun drillInto(region: Region) {
        _uiState.update { it.copy(path = it.path + region) }
        loadOptions(parent = region.code)
    }

    /** 한 단계 위로. 캐시가 있으므로 되돌아갈 때 서버를 다시 부르지 않는다. */
    fun onGoUp() {
        val state = uiState.value
        if (state.isLocked || state.path.isEmpty()) return

        val newPath = state.path.dropLast(1)
        _uiState.update { it.copy(path = newPath) }
        loadOptions(parent = newPath.lastOrNull()?.code)
    }

    fun onRetryOptions() {
        loadOptions(parent = uiState.value.path.lastOrNull()?.code)
    }

    private fun loadOptions(parent: String?) {
        optionsJob?.cancel()
        _uiState.update { it.copy(isLoadingOptions = true, optionsError = null, options = emptyList()) }

        optionsJob = viewModelScope.launch {
            regionRepository.getRegions(parentCode = parent).fold(
                onSuccess = { regions ->
                    // 서버가 displayName ASC 로 정렬해 준다 → 재정렬하지 않는다.
                    _uiState.update { it.copy(isLoadingOptions = false, options = regions) }
                },
                onFailure = { cause ->
                    _uiState.update {
                        it.copy(
                            isLoadingOptions = false,
                            optionsError = (cause as? AppError)?.userMessage
                                ?: "지역 목록을 불러오지 못했습니다.",
                        )
                    }
                },
            )
        }
    }

    // ──────────────────────── 선택 ────────────────────────

    /**
     * 동을 눌렀을 때의 선택/해제.
     *
     * 이미 고른 것을 다시 누르면 **해제**한다 — 목록에서 바로 취소할 수 있어야
     * 상한(2개)에 걸렸을 때 위쪽 칩까지 올라가지 않아도 된다.
     */
    private fun toggleSelection(region: Region) {
        val state = uiState.value
        if (state.isSelected(region)) {
            onRemoveSelected(region.code)
            return
        }
        if (!state.canSelectMore) {
            _uiState.update {
                it.copy(message = "동네는 최대 ${MAX_MY_LOCATIONS}개까지 설정할 수 있어요.")
            }
            return
        }
        _uiState.update { it.copy(selected = it.selected + region.toRef()) }
    }

    fun onRemoveSelected(code: String) {
        if (uiState.value.isLocked) return
        _uiState.update { it.copy(selected = it.selected.filterNot { ref -> ref.code == code }) }
    }

    /**
     * 대표 동네 지정 — 고른 것을 **맨 앞으로 옮긴다**.
     *
     * 서버에 "대표만 바꾸기" API 가 없고 **보낸 리스트의 0번**을 대표로 삼기 때문에,
     * 대표 변경은 곧 순서 변경이다. 그래서 별도 플래그를 두지 않고 순서 자체로 표현한다
     * (플래그와 순서를 둘 다 두면 언젠가 서로 어긋난다).
     */
    fun onMakePrimary(code: String) {
        if (uiState.value.isLocked) return
        _uiState.update { state ->
            val target = state.selected.firstOrNull { it.code == code } ?: return@update state
            state.copy(selected = listOf(target) + state.selected.filterNot { it.code == code })
        }
    }

    // ──────────────────────── 저장 ────────────────────────

    /**
     * 저장. 실패하면 **고른 것을 그대로 두고** 메시지만 띄운다 —
     * 여기서 초기화하면 사용자가 드릴다운을 처음부터 다시 해야 한다.
     */
    fun onSave() {
        val state = uiState.value
        if (!state.canSave) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, message = null) }

            memberRepository.updateMyLocations(state.selected.map { it.code }).fold(
                onSuccess = { locations ->
                    _uiState.update {
                        it.copy(isSaving = false, savedRegions = locations.map { loc -> loc.region })
                    }
                },
                onFailure = { cause ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            message = (cause as? AppError)?.userMessage ?: "동네를 저장하지 못했습니다.",
                        )
                    }
                },
            )
        }
    }

    /** 메시지를 한 번 보여준 뒤 지운다(회전할 때마다 같은 스낵바가 다시 뜨지 않게). */
    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    // ──────────────────────── 초기 로드 ────────────────────────

    /**
     * 이미 설정된 내 동네를 불러와 **초기 선택값**으로 둔다.
     *
     * 이게 없으면 화면이 "새로 설정" 만 되고 **기존 설정이 조용히 지워진다** —
     * `PUT` 이 전체 교체라, 동네 하나를 추가하려고 들어와서 하나만 고르고 저장하면
     * 원래 있던 다른 하나가 사라진다.
     *
     * 미설정 회원은 에러가 아니라 빈 리스트다(정상 성공) → 그대로 빈 선택으로 시작한다.
     */
    private fun loadMyLocations() {
        viewModelScope.launch {
            val mine = memberRepository.getMyLocations().getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    isLoadingMine = false,
                    // sortOrder 순서를 그대로 살린다 — 0번이 대표라는 의미가 순서에 실려 있다.
                    selected = mine.sortedBy { loc -> loc.sortOrder }.map { loc -> loc.region },
                )
            }
        }
    }
}
