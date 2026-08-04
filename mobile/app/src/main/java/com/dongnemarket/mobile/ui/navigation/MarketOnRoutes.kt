package com.dongnemarket.mobile.ui.navigation

/**
 * 화면 주소(route) 정의. Navigation Compose 는 화면을 문자열 경로로 식별한다 —
 * 웹의 URL 라우팅과 같은 개념이고, `{productId}` 는 경로 변수(Spring 의 `@PathVariable` 자리)다.
 *
 * 문자열을 화면 코드에 직접 쓰지 않고 여기 모아 두는 이유: 오타 하나가 런타임 크래시라서
 * 한 곳에서만 고칠 수 있게 만든다.
 */
object MarketOnRoutes {

    const val LOGIN = "login"
    const val HOME = "home"
    const val CHAT_LIST = "chatList"

    /** 상품 등록. 경로 인자가 없다 — 무엇을 등록할지는 화면 안에서 정한다. */
    const val PRODUCT_CREATE = "productCreate"

    /** 인자 이름 — NavHost 의 argument 선언과 화면에서 꺼낼 때 같은 값을 써야 한다. */
    const val ARG_PRODUCT_ID = "productId"
    const val ARG_ROOM_ID = "roomId"

    /** NavHost 에 등록할 패턴 */
    const val PRODUCT_DETAIL_PATTERN = "productDetail/{$ARG_PRODUCT_ID}"
    const val CHAT_ROOM_PATTERN = "chatRoom/{$ARG_ROOM_ID}"

    /** 실제 이동할 때 쓰는 주소 생성기 */
    fun productDetail(productId: Long): String = "productDetail/$productId"
    fun chatRoom(roomId: Long): String = "chatRoom/$roomId"
}
