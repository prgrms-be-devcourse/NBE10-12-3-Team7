package com.dongnemarket.auth.entity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class RefreshTokenTest {
    // ===== issue =====

    @Test
    @DisplayName("issue로 생성하면 memberId·token·expiresAt이 그대로 설정된다")
    fun issue_setsFields() {
        val expiresAt = LocalDateTime.now().plusDays(7)

        val refreshToken = RefreshToken.issue(1L, "token-value", expiresAt)

        assertThat(refreshToken.memberId).isEqualTo(1L)
        assertThat(refreshToken.token).isEqualTo("token-value")
        assertThat(refreshToken.expiresAt).isEqualTo(expiresAt)
    }

    // ===== replace =====

    @Test
    @DisplayName("replace를 호출하면 token과 expiresAt이 새 값으로 갱신된다")
    fun replace_updatesTokenAndExpiresAt() {
        val refreshToken = RefreshToken.issue(1L, "old-token", LocalDateTime.now())
        val newExpiresAt = LocalDateTime.now().plusDays(7)

        refreshToken.replace("new-token", newExpiresAt)

        assertThat(refreshToken.token).isEqualTo("new-token")
        assertThat(refreshToken.expiresAt).isEqualTo(newExpiresAt)
    }

    // ===== matches =====

    @Test
    @DisplayName("matches는 저장된 토큰과 같은 문자열이면 true를 반환한다")
    fun matches_sameToken_returnsTrue() {
        val refreshToken = RefreshToken.issue(1L, "token-value", LocalDateTime.now().plusDays(7))

        assertThat(refreshToken.matches("token-value")).isTrue()
    }

    @Test
    @DisplayName("matches는 저장된 토큰과 다른 문자열이면 false를 반환한다")
    fun matches_differentToken_returnsFalse() {
        val refreshToken = RefreshToken.issue(1L, "token-value", LocalDateTime.now().plusDays(7))

        assertThat(refreshToken.matches("other-value")).isFalse()
    }
}
