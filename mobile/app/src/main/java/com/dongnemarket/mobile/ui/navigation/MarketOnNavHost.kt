package com.dongnemarket.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dongnemarket.mobile.ui.chat.ChatListScreen
import com.dongnemarket.mobile.ui.chat.ChatRoomScreen
import com.dongnemarket.mobile.ui.home.HomeScreen
import com.dongnemarket.mobile.ui.login.LoginScreen
import com.dongnemarket.mobile.ui.productcreate.ProductCreateScreen
import com.dongnemarket.mobile.ui.productdetail.ProductDetailScreen

/**
 * 앱의 화면 지도(뒤로가기 스택 포함)를 한 곳에서 관리한다.
 *
 * 각 `composable(route) { ... }` 블록이 화면 하나다. Unit 1~4 의 실제 화면이 모두 연결되어 있다.
 *
 * 화면은 NavController 를 직접 받지 않고 **람다(onXxx)** 로 이동 의도만 알린다.
 * 이렇게 해야 화면이 네비게이션을 모르는 순수한 UI 가 되고 Compose 테스트에서 단독 실행할 수 있다.
 *
 * 경로 인자(`{productId}`·`{roomId}`)는 화면 파라미터로 넘기지 않는다 —
 * 각 ViewModel 이 `SavedStateHandle` 에서 [MarketOnRoutes.ARG_PRODUCT_ID]·[MarketOnRoutes.ARG_ROOM_ID]
 * 로 직접 꺼낸다. 그래서 아래 `arguments` 의 이름이 그 상수와 반드시 같아야 한다.
 */
@Composable
fun MarketOnNavHost(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = MarketOnRoutes.LOGIN,
    ) {
        // 로그인 — Unit 1
        composable(MarketOnRoutes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(MarketOnRoutes.HOME) {
                        // 로그인 성공 후 뒤로가기로 로그인 화면에 돌아오지 못하게 스택에서 제거
                        popUpTo(MarketOnRoutes.LOGIN) { inclusive = true }
                    }
                },
            )
        }

        // 홈 + 상품목록 — Unit 2
        composable(MarketOnRoutes.HOME) {
            HomeScreen(
                onProductClick = { productId ->
                    navController.navigate(MarketOnRoutes.productDetail(productId))
                },
                onChatTabClick = { navController.navigate(MarketOnRoutes.CHAT_LIST) },
                onCreateClick = { navController.navigate(MarketOnRoutes.PRODUCT_CREATE) },
            )
        }

        // 상품 등록 — Unit 5
        composable(MarketOnRoutes.PRODUCT_CREATE) {
            ProductCreateScreen(
                // 등록에 성공하면 방금 만든 상품 상세로 보낸다.
                onCreated = { productId ->
                    navController.navigate(MarketOnRoutes.productDetail(productId)) {
                        // 등록 화면을 스택에서 빼서 상세에서 뒤로가기 하면 홈으로 가게 한다.
                        // 안 빼면 이미 등록을 마친 폼으로 되돌아가고, 거기서 다시 누르면 중복 등록이다.
                        popUpTo(MarketOnRoutes.PRODUCT_CREATE) { inclusive = true }
                    }
                },
                onBackClick = { navController.popBackStack() },
            )
        }

        // 상품 상세 — Unit 3
        composable(
            route = MarketOnRoutes.PRODUCT_DETAIL_PATTERN,
            arguments = listOf(navArgument(MarketOnRoutes.ARG_PRODUCT_ID) { type = NavType.LongType }),
        ) {
            ProductDetailScreen(
                onBackClick = { navController.popBackStack() },
                // 채팅하기 → 방 생성(멱등)이 끝나면 그 방으로 이동한다.
                onChatCreated = { roomId ->
                    navController.navigate(MarketOnRoutes.chatRoom(roomId))
                },
            )
        }

        // 채팅 목록 — Unit 4
        composable(MarketOnRoutes.CHAT_LIST) {
            ChatListScreen(
                onRoomClick = { roomId ->
                    navController.navigate(MarketOnRoutes.chatRoom(roomId))
                },
                onBackClick = { navController.popBackStack() },
            )
        }

        // 채팅방 — Unit 4
        composable(
            route = MarketOnRoutes.CHAT_ROOM_PATTERN,
            arguments = listOf(navArgument(MarketOnRoutes.ARG_ROOM_ID) { type = NavType.LongType }),
        ) {
            ChatRoomScreen(
                onBackClick = { navController.popBackStack() },
            )
        }
    }
}
