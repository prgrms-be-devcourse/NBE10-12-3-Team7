package com.dongnemarket.auth.service

import com.dongnemarket.auth.repository.LoginAttemptRepository
import com.dongnemarket.global.exception.BusinessException
import com.dongnemarket.global.exception.ErrorCode
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * 로그인 실패 횟수를 세어 무차별 대입(brute-force) 시도를 차단한다.
 * 회원당(이메일당) 1개 카운터를 유지하며, 정해진 윈도우 내 실패 횟수가 임계값에 도달하면 그 윈도우가
 * 끝날 때까지 로그인 자체를 거부한다.
 *
 * 전환 규칙 — 임계값(5회)과 윈도우(10분) 상수, 세 메서드의 이름·시그니처를 그대로 유지한다.
 * 파라미터는 원본이 참조형 `String` 이라 nullable 로 둔다(descriptor 는 같지만, non-null 로 조이면
 * 아직 Java 인 `AuthService` 호출부에 런타임 null 검사가 삽입된다).
 * `@Transactional` 은 원본에 없었다 — 저장소가 Redis/InMemory 라 트랜잭션 경계를 새로 만들지 않는다.
 */
@Service
class LoginAttemptService(
    private val loginAttemptRepository: LoginAttemptRepository,
) {
    /** 임계값에 도달했으면 TOO_MANY_LOGIN_ATTEMPTS 예외를 던진다(실패 횟수를 추가로 늘리지 않는다). */
    fun assertNotBlocked(email: String?) {
        if (loginAttemptRepository.getFailureCount(email) >= MAX_ATTEMPTS) {
            throw BusinessException(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS)
        }
    }

    /** 로그인 실패(이메일 없음/비밀번호 불일치) 시 호출: 실패 횟수를 1 증가시킨다. */
    fun recordFailure(email: String?) {
        loginAttemptRepository.incrementFailure(email, LOCK_WINDOW)
    }

    /** 로그인 성공 시 호출: 실패 횟수를 초기화한다. */
    fun recordSuccess(email: String?) {
        loginAttemptRepository.resetFailure(email)
    }

    companion object {
        private const val MAX_ATTEMPTS = 5L
        private val LOCK_WINDOW: Duration = Duration.ofMinutes(10)
    }
}
