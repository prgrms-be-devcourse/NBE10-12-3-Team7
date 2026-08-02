package com.dongnemarket.mobile.data.repository

import com.dongnemarket.mobile.data.mapper.toDomain
import com.dongnemarket.mobile.data.remote.MemberApiService
import com.dongnemarket.mobile.data.remote.apiCall
import com.dongnemarket.mobile.data.remote.dto.MemberLocationUpdateRequestDto
import com.dongnemarket.mobile.domain.model.AppError
import com.dongnemarket.mobile.domain.model.Member
import com.dongnemarket.mobile.domain.model.MemberLocation
import com.dongnemarket.mobile.domain.repository.MemberRepository
import javax.inject.Inject

/**
 * [MemberRepository] 구현. 하는 일은 세 가지뿐이다:
 * API 호출 → DTO를 도메인 모델로 매핑 → `Result` 로 감싸 반환.
 *
 * 예외를 던지지 않는 규약은 `apiCall { }` 이 지켜 준다(예외를 `AppError` 로 번역해 `Result.failure` 에 담는다).
 */
class MemberRepositoryImpl @Inject constructor(
    private val api: MemberApiService,
) : MemberRepository {

    override suspend fun getMyProfile(): Result<Member> =
        apiCall { api.getMyProfile() }.map { dto -> dto.toDomain() }

    override suspend fun getMyLocations(): Result<List<MemberLocation>> =
        apiCall { api.getMyLocations() }.map { list -> list.map { it.toDomain() } }

    override suspend fun updateMyLocations(regionCodes: List<String>): Result<List<MemberLocation>> {
        // 서버로 보내기 전에 직접 검증한다.
        // 이유: 서버는 빈 배열·3개 초과·공백 원소·중복·미등록 지역명을 **모두 같은 코드**
        // (400 INVALID_INPUT_VALUE)로 뭉쳐서 주고, 어느 필드가 왜 틀렸는지 알려주지 않는다.
        // 그대로 화면에 띄우면 사용자는 무엇을 고쳐야 할지 알 수 없다.
        validate(regionCodes)?.let { message ->
            return Result.failure(AppError.Api(status = 400, code = null, message = message))
        }

        val request = MemberLocationUpdateRequestDto(regionCodes = regionCodes)
        return apiCall { api.updateMyLocations(request) }
            .map { list -> list.map { it.toDomain() } }
    }

    override suspend fun getActiveRegionName(): Result<String?> =
        getMyLocations().map { locations ->
            // 원칙은 active == true 인 항목이다.
            // 폴백(sortOrder 최솟값)을 둔 이유: active 는 서버가 sortOrder == 0 인 행에만 켜 주는데,
            // 만약 그 규칙이 흔들려 active 가 하나도 없는 응답이 오면 null 이 되고,
            // 호출부는 그것을 "동네 미설정" 으로 해석해 동네가 있는 사용자를 설정 화면으로 보내 버린다.
            // 목록이 비어 있을 때만 null 이 되도록 좁혀 둔다.
            val active = locations.firstOrNull { it.active }
                ?: locations.minByOrNull { it.sortOrder }
            active?.region?.display
        }

    /** 위반 사유를 사용자 문장으로 돌려준다. 문제가 없으면 null. */
    private fun validate(regionCodes: List<String>): String? = when {
        regionCodes.isEmpty() -> "동네를 최소 1개 선택해 주세요."
        // 서버 상수 MAX_REGION_FILTER_SIZE = 2. 3개 이상이면 400이다.
        regionCodes.size > MAX_REGIONS -> "동네는 최대 ${MAX_REGIONS}개까지 설정할 수 있어요."
        regionCodes.any { it.isBlank() } -> "동네 코드가 비어 있어요."
        // 코드는 공백·대소문자 변형이 없으므로 원문 비교로 중복을 판정하면 충분하다.
        regionCodes.distinct().size != regionCodes.size -> "같은 동네를 두 번 선택할 수 없어요."
        else -> null
    }

    private companion object {
        const val MAX_REGIONS = 2
    }
}
