package com.dongnemarket.mobile.ui.region

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dongnemarket.mobile.domain.model.Region
import com.dongnemarket.mobile.domain.model.RegionRef
import com.dongnemarket.mobile.ui.component.EmptyView
import com.dongnemarket.mobile.ui.component.ErrorView
import com.dongnemarket.mobile.ui.component.LoadingView
import com.dongnemarket.mobile.ui.theme.MarketOnTheme

// UI 테스트가 요소를 찾는 이름표.
internal const val TAG_REGION_LIST = "region_list"
internal const val TAG_REGION_ITEM = "region_item"
internal const val TAG_REGION_SELECTED_CHIP = "region_selected_chip"
internal const val TAG_REGION_SAVE = "region_save"
internal const val TAG_REGION_UP = "region_up"

/**
 * 동네 설정 화면.
 *
 * 다른 화면과 같은 규칙 — `NavController` 를 받지 않고 [onSaved]·[onBackClick] 람다로
 * 이동 "의도" 만 알린다. 그래야 Preview·Compose 테스트에서 단독으로 띄울 수 있다.
 *
 * @param onSaved 저장 성공. 이전 화면(홈·상품 등록)으로 돌아가라는 뜻이다.
 */
@Composable
fun RegionSettingScreen(
    onSaved: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RegionSettingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 저장 성공은 한 번 일어나고 끝나는 사건이라 상태를 보고 이동한다.
    // 이동 후 이 화면은 백스택에서 빠지므로 두 번 불릴 여지가 없다.
    LaunchedEffect(uiState.savedRegions) {
        if (uiState.savedRegions != null) onSaved()
    }

    RegionSettingContent(
        state = uiState,
        onRegionClick = viewModel::onRegionClick,
        onRemoveSelected = viewModel::onRemoveSelected,
        onMakePrimary = viewModel::onMakePrimary,
        onGoUp = viewModel::onGoUp,
        onRetryOptions = viewModel::onRetryOptions,
        onSave = viewModel::onSave,
        onMessageShown = viewModel::onMessageShown,
        onBackClick = onBackClick,
        modifier = modifier,
    )
}

/** ViewModel 없이 상태만 받아 그리는 본체. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionSettingContent(
    state: RegionSettingUiState,
    onRegionClick: (Region) -> Unit,
    onRemoveSelected: (String) -> Unit,
    onMakePrimary: (String) -> Unit,
    onGoUp: () -> Unit,
    onRetryOptions: () -> Unit,
    onSave: () -> Unit,
    onMessageShown: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onMessageShown()
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("내 동네 설정") },
                navigationIcon = {
                    // 위로 갈 곳이 있으면 한 단계 위로, 최상위면 화면을 닫는다.
                    // 뒤로가기 버튼이 두 가지 일을 하는 게 아니라, "한 단계 뒤로" 라는 한 가지 일을 한다.
                    IconButton(
                        onClick = { if (state.canGoUp) onGoUp() else onBackClick() },
                        enabled = !state.isLocked,
                        modifier = Modifier.testTag(TAG_REGION_UP),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (state.canGoUp) "상위 지역으로" else "뒤로",
                        )
                    }
                },
            )
        },
        bottomBar = {
            SaveBar(
                count = state.selected.size,
                enabled = state.canSave,
                isSaving = state.isSaving,
                onSave = onSave,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            SelectedRow(
                selected = state.selected,
                enabled = !state.isLocked,
                onRemove = onRemoveSelected,
                onMakePrimary = onMakePrimary,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            state.pathLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    state.isLoadingOptions -> LoadingView()

                    state.optionsError != null ->
                        ErrorView(message = state.optionsError) { onRetryOptions() }

                    state.options.isEmpty() -> EmptyView(message = "이 지역에는 하위 지역이 없어요.")

                    else -> RegionList(
                        options = state.options,
                        isSelected = state::isSelected,
                        enabled = !state.isLocked,
                        onClick = onRegionClick,
                    )
                }
            }
        }
    }
}

/**
 * 현재 단계 목록.
 *
 * 읍·면·동이면 **체크 표시**, 그 위 단계면 **꺾쇠(>)** 를 보여 준다.
 * 이 구분이 없으면 누르기 전까지 "고르는 것" 인지 "들어가는 것" 인지 알 수 없다.
 */
@Composable
private fun RegionList(
    options: List<Region>,
    isSelected: (Region) -> Boolean,
    enabled: Boolean,
    onClick: (Region) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(TAG_REGION_LIST),
    ) {
        items(items = options, key = { it.code }) { region ->
            val selected = isSelected(region)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onClick(region) }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .testTag(TAG_REGION_ITEM),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = region.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )

                if (region.isSelectable) {
                    // 고를 수 있는 단계(읍·면·동) — 이미 골랐으면 체크로 알린다.
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "선택됨",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    // 더 파고들 수 있는 단계 — 눌러도 선택되지 않는다는 걸 미리 알린다.
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "하위 지역 보기",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * 고른 동네 칩 줄.
 *
 * **첫 칩이 대표 동네**다(서버가 리스트 0번을 그렇게 만든다). 칩을 누르면 대표로 올라오고,
 * × 를 누르면 빠진다. 대표를 별도 토글로 두지 않은 이유는 서버에 "대표만 바꾸기" API 가 없어서
 * 대표 변경이 곧 **순서 변경**이기 때문이다 — 순서로 보이는 편이 사실에 가깝다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedRow(
    selected: List<RegionRef>,
    enabled: Boolean,
    onRemove: (String) -> Unit,
    onMakePrimary: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = "내 동네 (${selected.size}/$MAX_MY_LOCATIONS)",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.size(8.dp))

        if (selected.isEmpty()) {
            Text(
                text = "아래에서 동네를 골라 주세요. 읍·면·동까지 내려가야 고를 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            selected.forEachIndexed { index, ref ->
                AssistChip(
                    onClick = { onMakePrimary(ref.code) },
                    enabled = enabled,
                    label = {
                        Text(if (index == 0) "${ref.display} · 대표" else ref.display)
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "${ref.display} 빼기",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable(enabled = enabled) { onRemove(ref.code) },
                        )
                    },
                    modifier = Modifier.testTag(TAG_REGION_SELECTED_CHIP),
                )
            }
        }

        if (selected.size > 1) {
            Text(
                text = "칩을 누르면 대표 동네가 돼요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun SaveBar(
    count: Int,
    enabled: Boolean,
    isSaving: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier
                    // Scaffold 는 bottomBar 에 시스템 내비게이션 바 여백을 넣어 주지 않는다.
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Button(
                    onClick = onSave,
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_REGION_SAVE),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = when {
                            isSaving -> "저장하는 중"
                            count == 0 -> "동네를 골라 주세요"
                            else -> "${count}개 동네로 저장"
                        },
                    )
                }
            }
        }
    }
}

// ──────────────────────────── 미리보기 ────────────────────────────

@Preview(name = "동네 설정 · 시도 단계", showBackground = true, heightDp = 900)
@Composable
private fun RegionSettingPreview() {
    MarketOnTheme { PreviewShell(previewState()) }
}

@Preview(name = "동네 설정 · 읍면동 단계", showBackground = true, heightDp = 900)
@Composable
private fun RegionSettingDongPreview() {
    MarketOnTheme {
        PreviewShell(
            previewState().copy(
                path = listOf(previewRegion("11", "서울특별시", 1), previewRegion("1111", "종로구", 2)),
                options = listOf(
                    previewRegion("1111010100", "청운동", 3),
                    previewRegion("1111010200", "신교동", 3),
                    previewRegion("1111010300", "궁정동", 3),
                ),
                selected = listOf(
                    RegionRef("1111010100", "청운동", "서울특별시 종로구 청운동"),
                    RegionRef("1111010200", "신교동", "서울특별시 종로구 신교동"),
                ),
            ),
        )
    }
}

@Preview(name = "동네 설정 · 저장 중", showBackground = true, heightDp = 900)
@Composable
private fun RegionSettingSavingPreview() {
    MarketOnTheme {
        PreviewShell(
            previewState().copy(
                isSaving = true,
                selected = listOf(RegionRef("1111010100", "청운동", "서울특별시 종로구 청운동")),
            ),
        )
    }
}

@Composable
private fun PreviewShell(state: RegionSettingUiState) {
    RegionSettingContent(
        state = state,
        onRegionClick = {},
        onRemoveSelected = {},
        onMakePrimary = {},
        onGoUp = {},
        onRetryOptions = {},
        onSave = {},
        onMessageShown = {},
        onBackClick = {},
    )
}

private fun previewState() = RegionSettingUiState(
    isLoadingMine = false,
    options = listOf(
        previewRegion("11", "서울특별시", 1),
        previewRegion("26", "부산광역시", 1),
        previewRegion("27", "대구광역시", 1),
    ),
)

private fun previewRegion(code: String, name: String, level: Int) = Region(
    regionId = code.hashCode().toLong(),
    code = code,
    level = level,
    parentCode = if (level == 1) null else code.take(level * 2),
    fullName = name,
    displayName = name,
)
