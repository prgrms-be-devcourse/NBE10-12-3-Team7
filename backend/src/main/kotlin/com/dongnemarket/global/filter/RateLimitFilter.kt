package com.dongnemarket.global.filter

import com.dongnemarket.global.exception.ErrorCode
import com.dongnemarket.global.response.ErrorResponse
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * 클라이언트(IP)별 API 요청 속도를 슬라이딩 윈도우 방식으로 제한한다.
 *
 * 로그인 시도 제한(`auth.LoginAttemptService`, 고정 윈도우)과는 별개의, `/api/` 하위 모든
 * 요청(등록 URL 패턴은 RateLimitFilterConfig 참고)에 적용되는 범용 남용·과도한 트래픽 방지 장치다.
 *
 * 원본 Java 주석은 URL 패턴을 와일드카드 두 개로 적었는데, Kotlin 은 블록 주석이 **중첩**되어
 * 주석 안의 슬래시-별표 조합이 새 주석을 열고 KDoc 이 닫히지 않는다. Java 에는 없는 제약이라
 * 표기를 풀어 썼다.
 *
 * Redis ZSET에 "요청시각"을 원소로 쌓아두고, 매 요청마다 윈도우 밖(오래된) 원소를 제거한 뒤
 * 남은 개수로 한도를 판단한다 — 고정 윈도우와 달리 윈도우 경계에서 순간적으로 두 배 허용되는 문제가 없다.
 */
class RateLimitFilter(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val capacity: Long,
    windowSeconds: Long,
) : OncePerRequestFilter() {
    private val windowMillis: Long = windowSeconds * 1000

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val key = KEY_PREFIX + clientId(request)
        val now = System.currentTimeMillis()
        val windowStart = now - windowMillis

        // Redis ZSET score 는 double 이다. Kotlin 은 Long → Double 암묵 변환이 없어 toDouble() 이 필요하다.
        redisTemplate.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, windowStart.toDouble())
        val currentCount = redisTemplate.opsForZSet().zCard(key)

        if (currentCount != null && currentCount >= capacity) {
            writeTooManyRequests(response)
            return
        }

        redisTemplate.opsForZSet().add(key, "$now:${UUID.randomUUID()}", now.toDouble())
        redisTemplate.expire(key, Duration.ofMillis(windowMillis + 1000))

        filterChain.doFilter(request, response)
    }

    /** 프록시/로드밸런서를 거치는 경우를 대비해 X-Forwarded-For를 우선 확인하고, 없으면 원격 주소를 쓴다. */
    private fun clientId(request: HttpServletRequest): String {
        // Java 의 StringUtils.hasText(s) 와 동일한 판정(null·빈문자·공백만 = false)이지만,
        // isNullOrBlank() 는 계약(contract)이 있어 블록 안에서 forwarded 가 String 으로 스마트 캐스트된다.
        val forwarded = request.getHeader("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) {
            return forwarded.split(",")[0].trim()
        }
        return request.remoteAddr
    }

    private fun writeTooManyRequests(response: HttpServletResponse) {
        response.status = ErrorCode.TOO_MANY_REQUESTS.status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        objectMapper.writeValue(response.writer, ErrorResponse.of(ErrorCode.TOO_MANY_REQUESTS))
    }

    companion object {
        private const val KEY_PREFIX = "ratelimit:"
    }
}
