package com.dongnemarket.mobile.domain.repository

import com.dongnemarket.mobile.domain.model.Member
import com.dongnemarket.mobile.domain.model.MemberLocation

/**
 * 내 정보와 "내 동네" 설정을 담당한다.
 *
 * 규약: **예외를 던지지 않는다.** 모든 실패는 `Result.failure(AppError)` 로 돌아온다.
 */
interface MemberRepository {

    /**
     * 내 정보 조회(`GET /api/members/me`).
     *
     * **이 호출이 앱의 세션 확인 수단이다.** 홈·상품목록·카테고리·지역 같은 무인증 경로는
     * 토큰이 만료·변조돼도 401 대신 200 + 익명 취급으로 응답하기 때문에,
     * 그 화면들만 보고 있으면 토큰 만료를 영원히 눈치채지 못한다.
     * 그래서 앱 시작(또는 인증 필요 화면 진입) 시 이 호출로 세션을 검증한다.
     *
     * 실패가 `AppError.Unauthorized` 면 → 토큰 폐기 후 로그인 화면.
     *
     * 반환된 `Member.memberId` 는 채팅 좌/우 말풍선 판정에 쓰이므로 화면에서 보관해 둘 것.
     */
    suspend fun getMyProfile(): Result<Member>

    /**
     * 내 동네 목록 조회(`GET /api/members/me/locations`).
     *
     * ⚠️ **미설정 회원은 에러가 아니라 빈 리스트다.** `emptyList()` 는 정상 성공이며,
     * 이때는 홈이 아니라 동네 설정 화면으로 라우팅해야 한다.
     *
     * 정지/탈퇴 계정에 대해 이 API 는 401이 아니라 403/400/404 를 준다
     * → `AppError.Unauthorized` 가 아니므로 토큰 갱신 로직으로 분기하면 안 된다.
     */
    suspend fun getMyLocations(): Result<List<MemberLocation>>

    /**
     * 내 동네 설정(`PUT /api/members/me/locations`). 응답은 갱신된 전체 목록이다.
     *
     * ⚠️ **전체 교체**다. 기존 설정을 지우고 넘긴 리스트로 다시 만든다(부분 추가·단건 삭제 API 없음).
     * ⚠️ **리스트 0번 원소가 서버에 의해 대표 동네(`active = true`)가 된다.**
     *     대표 동네만 바꾸는 API 는 없으므로 순서를 바꿔 전체를 다시 보낸다.
     *
     * @param regions 지역 목록 API(`GET /api/regions`)에서 받은 `name` **원문 그대로**.
     *   1~2개, 중복 불가, 공백 원소 불가. 서버는 이 위반들을 전부 같은 코드(400 `INVALID_INPUT_VALUE`)로
     *   뭉쳐서 주고 어느 필드가 틀렸는지 알려주지 않으므로, 구현체가 **호출 전에 미리 검증**해
     *   사람이 읽을 수 있는 실패 메시지를 돌려준다.
     */
    suspend fun updateMyLocations(regionCodes: List<String>): Result<List<MemberLocation>>

    /**
     * 대표 동네 이름만 뽑아 주는 편의 함수. **홈 헤더가 이것을 쓴다.**
     *
     * @return 대표 동네 이름, 동네를 아직 설정하지 않았으면 `null`.
     *   `Result.success(null)` 은 "조회는 성공했고 설정된 동네가 없다"는 뜻이므로,
     *   실패(`Result.failure`)와 구분해서 다뤄야 한다 → null 이면 동네 설정 화면으로 보낸다.
     */
    suspend fun getActiveRegionName(): Result<String?>
}
