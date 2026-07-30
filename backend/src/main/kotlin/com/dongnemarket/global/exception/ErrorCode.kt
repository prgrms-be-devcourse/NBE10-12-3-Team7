package com.dongnemarket.global.exception

/**
 * 전역 ErrorCode.
 *
 * 각 팀원은 **자기 담당 도메인 주석 영역에만** ErrorCode 를 추가한다.
 * COMMON 영역은 팀장만 수정한다. (00-ai-common-rules.md §4)
 *
 * Java 의 `private final int status` + `getStatus()` 조합이 주 생성자 `val status: Int` 하나로 대체된다.
 * Java 에서 보이는 접근자 이름(`getStatus()`/`getCode()`/`getMessage()`)은 그대로라 호출부 수정이 없다.
 */
enum class ErrorCode(
    val status: Int,
    val code: String,
    val message: String,
) {
    // ===== COMMON ERROR (팀장만 수정) =====
    INTERNAL_SERVER_ERROR(500, "COMMON_001", "서버 내부 오류가 발생했습니다."),
    INVALID_INPUT_VALUE(400, "COMMON_002", "잘못된 입력값입니다."),
    UNAUTHORIZED(401, "COMMON_003", "인증이 필요합니다."),
    FORBIDDEN(403, "COMMON_004", "접근 권한이 없습니다."),

    // ===== AUTH ERROR (김대연) =====
    DUPLICATE_EMAIL(409, "AUTH_001", "이미 사용 중인 이메일입니다."),
    DUPLICATE_NICKNAME(409, "AUTH_002", "이미 사용 중인 닉네임입니다."),
    INVALID_PASSWORD(401, "AUTH_003", "비밀번호가 일치하지 않습니다."),
    INVALID_TOKEN(401, "AUTH_004", "유효하지 않은 토큰입니다."),
    EXPIRED_REFRESH_TOKEN(401, "AUTH_005", "Refresh Token이 만료되었습니다. 다시 로그인해주세요."),
    INVALID_REFRESH_TOKEN(401, "AUTH_006", "유효하지 않은 Refresh Token입니다."),
    REFRESH_TOKEN_NOT_FOUND(401, "AUTH_007", "Refresh Token 정보를 찾을 수 없습니다. 다시 로그인해주세요."),
    EMAIL_SEND_FAILED(500, "AUTH_008", "이메일 발송에 실패했습니다."),
    EMAIL_VERIFICATION_REQUEST_TOO_SOON(429, "AUTH_009", "인증 코드를 너무 자주 요청했습니다. 잠시 후 다시 시도해주세요."),
    EMAIL_VERIFICATION_NOT_FOUND(404, "AUTH_010", "이메일 인증 요청 내역을 찾을 수 없습니다."),
    INVALID_VERIFICATION_CODE(400, "AUTH_011", "인증 코드가 일치하지 않습니다."),
    EXPIRED_VERIFICATION_CODE(400, "AUTH_012", "인증 코드가 만료되었습니다. 다시 요청해주세요."),
    EMAIL_NOT_VERIFIED(400, "AUTH_013", "이메일 인증을 먼저 완료해주세요."),
    SAME_AS_OLD_PASSWORD(400, "AUTH_014", "새 비밀번호는 현재 비밀번호와 달라야 합니다."),
    INVALID_RESET_TOKEN(400, "AUTH_015", "유효하지 않은 비밀번호 재설정 링크입니다. 다시 요청해주세요."),
    EXPIRED_RESET_TOKEN(400, "AUTH_016", "비밀번호 재설정 링크가 만료되었습니다. 다시 요청해주세요."),
    TERMS_NOT_AGREED(400, "AUTH_017", "이용약관에 동의해야 합니다."),
    PERSONAL_INFO_COLLECTION_NOT_AGREED(400, "AUTH_018", "개인정보 수집 및 이용에 동의해야 합니다."),
    TOO_MANY_LOGIN_ATTEMPTS(429, "AUTH_019", "로그인 시도가 너무 많습니다. 10분 후 다시 시도해주세요."),
    INVALID_OAUTH_STATE(400, "AUTH_020", "유효하지 않거나 만료된 소셜 로그인 요청입니다. 다시 시도해주세요."),
    OAUTH_EMAIL_NOT_PROVIDED(400, "AUTH_021", "소셜 계정에서 이메일 제공에 동의해주세요."),
    OAUTH_EMAIL_NOT_VERIFIED(400, "AUTH_022", "인증되지 않은 이메일은 소셜 로그인에 사용할 수 없습니다."),
    OAUTH_EMAIL_CONFLICT(409, "AUTH_023", "이미 사용 중인 이메일입니다. 다른 방법으로 로그인해주세요."),
    OAUTH_PROVIDER_ERROR(502, "AUTH_024", "소셜 로그인 제공자 응답 처리 중 오류가 발생했습니다."),
    TOO_MANY_OAUTH_ATTEMPTS(429, "AUTH_025", "진행 중인 소셜 로그인 시도가 너무 많습니다. 잠시 후 다시 시도해주세요."),
    SOCIAL_ONLY_ACCOUNT_PASSWORD_CHANGE(400, "AUTH_026", "소셜 로그인 전용 계정은 비밀번호를 변경할 수 없습니다."),
    OAUTH_AUTHORIZATION_FAILED(400, "AUTH_027", "소셜 로그인 인가 코드가 유효하지 않거나 만료되었습니다. 다시 시도해주세요."),

    // ===== MEMBER ERROR (김대연) =====
    MEMBER_NOT_FOUND(404, "MEMBER_001", "회원을 찾을 수 없습니다."),
    DELETED_MEMBER(400, "MEMBER_002", "탈퇴한 회원입니다."),
    SUSPENDED_MEMBER(403, "MEMBER_003", "정지된 회원입니다."),

    // ===== PRODUCT ERROR (한상민) =====
    PRODUCT_NOT_FOUND(404, "PRODUCT_001", "상품을 찾을 수 없습니다."),
    PRODUCT_OWNER_ONLY(403, "PRODUCT_002", "상품 작성자만 처리할 수 있습니다."),
    HIDDEN_PRODUCT(403, "PRODUCT_003", "숨김 처리된 상품입니다."),
    DELETED_PRODUCT(404, "PRODUCT_004", "삭제된 상품입니다."),
    INVALID_PRODUCT_TITLE(400, "PRODUCT_005", "상품 제목은 필수입니다."),
    INVALID_PRODUCT_PRICE(400, "PRODUCT_006", "상품 가격은 0원 이상이어야 합니다."),
    CANNOT_UPDATE_COMPLETED_PRODUCT(400, "PRODUCT_007", "거래완료된 상품은 수정할 수 없습니다."),

    // ===== CATEGORY ERROR (한상민) =====
    CATEGORY_NOT_FOUND(404, "CATEGORY_001", "카테고리를 찾을 수 없습니다."),

    // ===== TRADE ERROR (한상민) =====
    INVALID_TRADE_STATUS(400, "TRADE_001", "잘못된 거래 상태입니다."),
    CANNOT_CHANGE_COMPLETED_PRODUCT(400, "TRADE_002", "거래완료된 상품은 상태를 변경할 수 없습니다."),

    // ===== SEARCH ERROR (한상민) =====
    INVALID_SEARCH_CONDITION(400, "SEARCH_001", "잘못된 검색 조건입니다."),

    // ===== FAVORITE ERROR (권건우) =====
    FAVORITE_ALREADY_EXISTS(409, "FAVORITE_001", "이미 관심 등록한 상품입니다."),
    FAVORITE_NOT_FOUND(404, "FAVORITE_002", "관심 상품을 찾을 수 없습니다."),
    CANNOT_FAVORITE_OWN_PRODUCT(400, "FAVORITE_003", "본인이 등록한 상품은 관심 등록할 수 없습니다."),

    // ===== COMMENT ERROR (권건우) =====
    COMMENT_NOT_FOUND(404, "COMMENT_001", "댓글을 찾을 수 없습니다."),
    COMMENT_OWNER_ONLY(403, "COMMENT_002", "댓글 작성자만 처리할 수 있습니다."),

    // ===== CHAT ERROR (권건우) =====
    CHAT_ROOM_NOT_FOUND(404, "CHAT_001", "채팅방을 찾을 수 없습니다."),
    CHAT_ACCESS_DENIED(403, "CHAT_002", "채팅방 참여자만 접근할 수 있습니다."),
    CANNOT_CHAT_WITH_SELF(400, "CHAT_003", "자신의 상품에는 채팅을 시작할 수 없습니다."),
    CHAT_PARTNER_WITHDRAWN(400, "CHAT_004", "탈퇴한 상대와는 더 이상 대화할 수 없습니다."),

    // ===== REPORT ERROR (서유진) =====
    REPORT_NOT_FOUND(404, "REPORT_001", "신고 내역을 찾을 수 없습니다."),
    CANNOT_REPORT_OWN_PRODUCT(400, "REPORT_002", "본인이 등록한 상품은 신고할 수 없습니다."),
    CANNOT_REPORT_SELF(400, "REPORT_003", "본인 계정은 신고할 수 없습니다."),
    INVALID_REPORT_TARGET(400, "REPORT_004", "잘못된 신고 대상입니다."),
    DUPLICATE_REPORT(409, "REPORT_005", "이미 신고한 대상입니다."),
    CANNOT_CANCEL_REPORT(400, "REPORT_006", "이미 처리 중이거나 완료된 신고는 취소할 수 없습니다."),
    REPORT_OWNER_ONLY(403, "REPORT_007", "본인이 신고한 내역만 조회·취소할 수 있습니다."),
    INVALID_EVIDENCE_IMAGE(400, "REPORT_008", "이미지 파일(jpg/png/gif/webp)만 5MB 이하로 업로드할 수 있습니다."),
    EVIDENCE_IMAGE_NOT_FOUND(404, "REPORT_009", "증빙 이미지를 찾을 수 없습니다."),
    EVIDENCE_IMAGE_UPLOAD_FAILED(500, "REPORT_010", "이미지 업로드 중 오류가 발생했습니다."),
    TOO_MANY_REQUESTS(429, "REPORT_011", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),

    // ===== MANNER ERROR (서유진) =====
    MANNER_RATING_TRADE_NOT_COMPLETED(400, "MANNER_001", "거래가 완료된 상품만 후기를 등록할 수 있습니다."),
    MANNER_RATING_NOT_A_PARTICIPANT(403, "MANNER_002", "해당 거래의 구매자만 후기를 등록할 수 있습니다."),
    MANNER_RATING_ALREADY_EXISTS(409, "MANNER_003", "이미 해당 거래에 대한 후기를 등록했습니다."),

    // ===== ESCROW ERROR (조민석) =====
    ESCROW_NOT_FOUND(404, "ESCROW_001", "거래를 찾을 수 없습니다."),
    CANNOT_ESCROW_OWN_PRODUCT(400, "ESCROW_002", "본인 상품은 거래할 수 없습니다."),
    PRODUCT_NOT_ON_SALE(400, "ESCROW_003", "판매 중인 상품이 아닙니다."),
    ESCROW_NOT_IN_ESCROW(409, "ESCROW_004", "예치 상태가 아니어서 구매확정할 수 없습니다."),
    ESCROW_NOT_CANCELABLE(409, "ESCROW_005", "예치 상태가 아니어서 취소할 수 없습니다."),
    ESCROW_ACCESS_DENIED(403, "ESCROW_006", "본인 거래만 확정·취소할 수 있습니다."),
    ESCROW_ALREADY_EXISTS(409, "ESCROW_007", "이미 진행 중인 거래가 있는 상품입니다."),

    // ===== ADMIN ERROR (팀장) =====
    ADMIN_ONLY(403, "ADMIN_001", "관리자만 접근할 수 있습니다."),
    INVALID_MEMBER_STATUS(400, "ADMIN_002", "잘못된 회원 상태 값입니다."),
    INVALID_REPORT_STATUS(400, "ADMIN_003", "잘못된 신고 상태 값입니다."),
    STORAGE_ORPHAN_DELETE_FAILED(500, "ADMIN_004", "고아 파일 삭제 중 오류가 발생했습니다."),
    INVALID_STORAGE_DIRECTORY(400, "ADMIN_005", "허용되지 않은 저장소 디렉터리입니다."),

    // ===== AUCTION ERROR =====
    AUCTION_NOT_FOUND(404, "AUCTION_001", "경매를 찾을 수 없습니다."),
    AUCTION_NOT_ONGOING(409, "AUCTION_002", "진행 중인 경매가 아닙니다."),
    BID_TOO_LOW(400, "AUCTION_003", "현재가보다 높은 금액만 입찰할 수 있습니다."),
    AUCTION_BID_CONFLICT(409, "AUCTION_004", "다른 입찰이 먼저 처리되었습니다. 최신 현재가로 다시 입찰해주세요."),
}
