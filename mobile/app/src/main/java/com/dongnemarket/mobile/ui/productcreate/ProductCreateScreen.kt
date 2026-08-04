package com.dongnemarket.mobile.ui.productcreate

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dongnemarket.mobile.domain.model.Category
import com.dongnemarket.mobile.domain.model.MemberLocation
import com.dongnemarket.mobile.domain.model.RegionRef
import com.dongnemarket.mobile.ui.component.ErrorView
import com.dongnemarket.mobile.ui.component.LoadingView
import com.dongnemarket.mobile.ui.productcreate.component.ImagePickerRow
import com.dongnemarket.mobile.ui.productcreate.component.SelectionChipGroup
import com.dongnemarket.mobile.ui.theme.MarketOnTheme

// UI 테스트가 요소를 찾는 이름표.
internal const val TAG_CREATE_TITLE = "create_title"
internal const val TAG_CREATE_PRICE = "create_price"
internal const val TAG_CREATE_DESCRIPTION = "create_description"
internal const val TAG_CREATE_SUBMIT = "create_submit"

/**
 * 상품 등록 화면.
 *
 * 홈·상세와 같은 규칙을 따른다 — `NavController` 를 받지 않고 [onCreated]·[onBackClick] 람다로
 * 이동 "의도"만 알린다. 그래야 Preview 와 Compose 테스트에서 단독으로 띄울 수 있다.
 *
 * @param onCreated 등록 성공. 인자는 새로 만들어진 `productId` — 상세로 보내라는 뜻이다.
 */
@Composable
fun ProductCreateScreen(
    onCreated: (Long) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProductCreateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    /**
     * 등록 성공은 **한 번 일어나고 끝나는 사건**이라 상태를 보고 이동한다.
     * `phase` 를 key 로 잡았기 때문에 회전 등으로 재구성돼도 `Done` 인 동안 한 번만 실행된다.
     * (이동한 뒤 이 화면은 백스택에서 빠지므로 두 번 불릴 여지가 없다.)
     */
    LaunchedEffect(uiState.phase) {
        val phase = uiState.phase
        if (phase is CreatePhase.Done) onCreated(phase.productId)
    }

    ProductCreateContent(
        state = uiState,
        onBackClick = onBackClick,
        onImagesPicked = viewModel::onImagesPicked,
        onRemoveImage = viewModel::onRemoveImage,
        onThumbnailSelect = viewModel::onThumbnailSelect,
        onTitleChange = viewModel::onTitleChange,
        onDescriptionChange = viewModel::onDescriptionChange,
        onPriceChange = viewModel::onPriceChange,
        onCategorySelect = viewModel::onCategorySelect,
        onRegionSelect = viewModel::onRegionSelect,
        onSubmit = viewModel::onSubmit,
        onSubmitErrorShown = viewModel::onSubmitErrorShown,
        onRetryLoad = viewModel::loadForm,
        modifier = modifier,
    )
}

/**
 * ViewModel 없이 상태만 받아 그리는 본체.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductCreateContent(
    state: ProductCreateUiState,
    onBackClick: () -> Unit,
    onImagesPicked: (List<String>) -> Unit,
    onRemoveImage: (Int) -> Unit,
    onThumbnailSelect: (Int) -> Unit,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onPriceChange: (String) -> Unit,
    onCategorySelect: (Long) -> Unit,
    onRegionSelect: (String) -> Unit,
    onSubmit: () -> Unit,
    onSubmitErrorShown: () -> Unit,
    onRetryLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    /**
     * 시스템 사진 선택기.
     *
     * `PickMultipleVisualMedia` 를 쓰면 **권한을 요청하지 않는다** —
     * 선택기는 별도 프로세스에서 돌고 사용자가 고른 항목의 URI 만 우리에게 건네준다.
     * 예전 방식(`READ_EXTERNAL_STORAGE` 권한 + MediaStore 질의)은 갤러리 전체 접근을 요구했고,
     * 사진 5장을 올리려고 사진 전부를 내주는 건 과한 요구다.
     *
     * 돌아오는 `Uri` 를 그대로 들고 다니지 않고 `toString()` 하는 이유는
     * [com.dongnemarket.mobile.domain.model.NewProduct] KDoc 참고(Domain 을 Android 에서 떼어 놓기 위함).
     */
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_PRODUCT_IMAGES),
    ) { uris -> onImagesPicked(uris.map { it.toString() }) }

    // 서버·통신 실패는 스낵바로 알리고 폼 입력은 그대로 둔다(다시 타이핑하게 만들지 않는다).
    LaunchedEffect(state.submitError) {
        val message = state.submitError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onSubmitErrorShown()
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("내 물건 팔기") },
                navigationIcon = {
                    IconButton(onClick = onBackClick, enabled = !state.isFormLocked) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
        bottomBar = {
            SubmitBar(
                phase = state.phase,
                enabled = state.isSubmitEnabled,
                onSubmit = onSubmit,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.isLoadingForm -> LoadingView()

                state.formLoadError != null -> ErrorView(message = state.formLoadError) { onRetryLoad() }

                // 동네가 없으면 regionCode 를 만들 수 없어 등록이 원천적으로 불가능하다.
                // 빈 폼을 보여 주고 제출에서 막으면 사용자는 왜 안 되는지 알 수 없다.
                state.hasNoRegion -> NoRegionNotice()

                else -> ProductCreateForm(
                    state = state,
                    onAddImageClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onRemoveImage = onRemoveImage,
                    onThumbnailSelect = onThumbnailSelect,
                    onTitleChange = onTitleChange,
                    onDescriptionChange = onDescriptionChange,
                    onPriceChange = onPriceChange,
                    onCategorySelect = onCategorySelect,
                    onRegionSelect = onRegionSelect,
                )
            }
        }
    }
}

@Composable
private fun ProductCreateForm(
    state: ProductCreateUiState,
    onAddImageClick: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onThumbnailSelect: (Int) -> Unit,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onPriceChange: (String) -> Unit,
    onCategorySelect: (Long) -> Unit,
    onRegionSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // 키보드가 올라오면 아래 입력칸이 가려지므로 반드시 스크롤 가능해야 한다.
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // ── 사진 ──
        FieldSection(label = "사진", error = state.fieldErrors.images, contentPadding = 0.dp) {
            ImagePickerRow(
                imageUris = state.imageUris,
                thumbnailIndex = state.thumbnailIndex,
                enabled = !state.isFormLocked,
                onAddClick = onAddImageClick,
                onRemove = onRemoveImage,
                onThumbnailSelect = onThumbnailSelect,
            )
            if (state.imageUris.size > 1) {
                Text(
                    text = "사진을 탭하면 대표 사진으로 지정돼요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                )
            }
        }

        // ── 제목 ──
        FieldSection(label = "제목", error = state.fieldErrors.title) {
            OutlinedTextField(
                value = state.title,
                onValueChange = onTitleChange,
                enabled = !state.isFormLocked,
                isError = state.fieldErrors.title != null,
                singleLine = true,
                placeholder = { Text("상품명을 입력해 주세요") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_CREATE_TITLE),
            )
        }

        // ── 가격 ──
        FieldSection(label = "가격", error = state.fieldErrors.price) {
            OutlinedTextField(
                value = state.priceInput,
                onValueChange = onPriceChange,
                enabled = !state.isFormLocked,
                isError = state.fieldErrors.price != null,
                singleLine = true,
                placeholder = { Text("0") },
                prefix = { Text("₩") },
                // 숫자 키패드를 띄운다. ViewModel 이 어차피 숫자만 남기지만,
                // 키보드부터 숫자로 주는 편이 "왜 글자가 안 써지지?" 를 막는다.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_CREATE_PRICE),
            )
            Text(
                text = "0원으로 두면 나눔으로 등록돼요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ── 카테고리 ──
        FieldSection(label = "카테고리", error = state.fieldErrors.category) {
            SelectionChipGroup(
                options = state.categories.map { it.id to it.name },
                selected = state.selectedCategoryId,
                enabled = !state.isFormLocked,
                onSelect = onCategorySelect,
            )
        }

        // ── 동네 ──
        FieldSection(label = "거래 동네", error = state.fieldErrors.region) {
            SelectionChipGroup(
                options = state.myLocations.map { it.region.code to it.region.display },
                selected = state.selectedRegionCode,
                enabled = !state.isFormLocked,
                onSelect = onRegionSelect,
            )
            Text(
                // 왜 여기 내 동네만 나오는지 설명한다. 설명이 없으면 "다른 동네는 왜 없지?" 가 된다.
                text = "내 동네에서만 상품을 등록할 수 있어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ── 설명 ──
        FieldSection(label = "설명") {
            OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                enabled = !state.isFormLocked,
                placeholder = { Text("상품 상태, 구매 시기 등을 적어 주세요") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .testTag(TAG_CREATE_DESCRIPTION),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * 라벨 + 내용 + (있으면) 오류 문구를 묶는 한 칸.
 *
 * 오류를 입력칸 **바로 아래**에 두는 것이 이 컴포넌트의 목적이다.
 * 스낵바 하나로 몰아 보여 주면 "어느 칸이 문제인지" 정보가 사라지는데,
 * 서버가 위반을 `INVALID_INPUT_VALUE` 하나로 뭉쳐 주기 때문에 그 정보는 앱만 갖고 있다.
 *
 * @param contentPadding 사진 줄은 화면 끝까지 스크롤돼야 해서 0을 넘긴다.
 */
@Composable
private fun FieldSection(
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    contentPadding: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        Column(modifier = Modifier.padding(horizontal = contentPadding)) {
            content()
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(
                        // 사진 줄(contentPadding = 0)일 때도 문구는 다른 칸과 같은 선에 맞춘다.
                        start = if (contentPadding == 0.dp) 16.dp else 0.dp,
                        top = 6.dp,
                    ),
                )
            }
        }
    }
}

/** 동네 미설정 안내. 앱에 동네 설정 화면이 아직 없어 "어디서 설정하라"고 말할 수 없다. */
@Composable
private fun NoRegionNotice(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "동네를 먼저 설정해 주세요",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "상품은 내 동네에만 등록할 수 있어요.\n동네 설정 기능은 준비 중이에요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 하단 제출 바. [CreatePhase] 에 따라 문구가 바뀐다.
 *
 * 진행률을 버튼 안에 넣은 이유: 사진 5장을 올리는 데 수 초가 걸릴 수 있는데
 * 스피너만 돌면 사용자는 멈춘 건지 되는 건지 모른다. "2/5장" 은 **진행 중이라는 증거**다.
 */
@Composable
private fun SubmitBar(
    phase: CreatePhase,
    enabled: Boolean,
    onSubmit: () -> Unit,
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
                    onClick = onSubmit,
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_CREATE_SUBMIT),
                ) {
                    if (phase !is CreatePhase.Editing && phase !is CreatePhase.Done) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = when (phase) {
                            is CreatePhase.Uploading -> "사진 올리는 중 ${phase.done}/${phase.total}"
                            CreatePhase.Creating -> "등록하는 중"
                            else -> "등록 완료"
                        },
                    )
                }
            }
        }
    }
}

// ──────────────────────────── 미리보기 ────────────────────────────

@Preview(name = "등록 · 입력", showBackground = true, heightDp = 900)
@Composable
private fun ProductCreateContentPreview() {
    MarketOnTheme { PreviewShell(previewState()) }
}

@Preview(name = "등록 · 검증 실패", showBackground = true, heightDp = 900)
@Composable
private fun ProductCreateErrorPreview() {
    MarketOnTheme {
        PreviewShell(
            previewState().copy(
                imageUris = emptyList(),
                title = "",
                priceInput = "",
                selectedCategoryId = null,
                fieldErrors = FieldErrors(
                    images = "사진을 1장 이상 등록해 주세요.",
                    title = "제목을 입력해 주세요.",
                    price = "가격을 입력해 주세요.",
                    category = "카테고리를 선택해 주세요.",
                ),
            ),
        )
    }
}

@Preview(name = "등록 · 업로드 중", showBackground = true, heightDp = 900)
@Composable
private fun ProductCreateUploadingPreview() {
    MarketOnTheme {
        PreviewShell(previewState().copy(phase = CreatePhase.Uploading(done = 2, total = 3)))
    }
}

@Preview(name = "등록 · 동네 미설정", showBackground = true, heightDp = 500)
@Composable
private fun ProductCreateNoRegionPreview() {
    MarketOnTheme {
        PreviewShell(previewState().copy(myLocations = emptyList(), selectedRegionCode = null))
    }
}

@Composable
private fun PreviewShell(state: ProductCreateUiState) {
    ProductCreateContent(
        state = state,
        onBackClick = {},
        onImagesPicked = {},
        onRemoveImage = {},
        onThumbnailSelect = {},
        onTitleChange = {},
        onDescriptionChange = {},
        onPriceChange = {},
        onCategorySelect = {},
        onRegionSelect = {},
        onSubmit = {},
        onSubmitErrorShown = {},
        onRetryLoad = {},
    )
}

private fun previewState() = ProductCreateUiState(
    isLoadingForm = false,
    categories = listOf(
        Category(1L, "디지털기기"),
        Category(2L, "생활가전"),
        Category(3L, "가구/인테리어"),
        Category(4L, "의류"),
    ),
    myLocations = listOf(
        MemberLocation(
            region = RegionRef("1168010300", "역삼동", "서울특별시 강남구 역삼동"),
            sortOrder = 0,
            active = true,
        ),
        MemberLocation(
            region = RegionRef("1168010600", "삼성동", "서울특별시 강남구 삼성동"),
            sortOrder = 1,
            active = false,
        ),
    ),
    // Preview 는 실제 파일을 못 읽으므로 썸네일 자리는 비어 보인다(레이아웃 확인용).
    imageUris = listOf("preview://1", "preview://2"),
    thumbnailIndex = 0,
    title = "거의 새것 닌텐도 스위치",
    priceInput = "240000",
    description = "작년에 사서 몇 번 안 했어요.",
    selectedCategoryId = 1L,
    selectedRegionCode = "1168010300",
)
