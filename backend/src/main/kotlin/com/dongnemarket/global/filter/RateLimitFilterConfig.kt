package com.dongnemarket.global.filter

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * RateLimitFilter를 서블릿 컨테이너 필터 체인에 등록한다. Spring Security 필터 체인보다 앞단(가장 먼저)에서
 * 동작해, 한도를 초과한 요청은 인증·인가 등 이후 처리를 전혀 거치지 않고 즉시 거부된다.
 *
 * test 프로파일은 RedisAutoConfiguration이 빠져 StringRedisTemplate 빈이 없으므로 이 설정 자체를 제외한다
 * (auth의 RedisLoginAttemptRepository와 동일한 방식).
 */
@Configuration
@Profile("!test")
class RateLimitFilterConfig(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    /**
     * `@Value` 의 `${...}` 는 Kotlin 문자열 템플릿과 문법이 겹친다.
     * `$` 를 `\$` 로 이스케이프해야 프로퍼티 플레이스홀더로 그대로 전달된다.
     */
    @Bean
    fun rateLimitFilterRegistration(
        @Value("\${rate-limit.capacity:60}") capacity: Long,
        @Value("\${rate-limit.window-seconds:10}") windowSeconds: Long,
    ): FilterRegistrationBean<RateLimitFilter> {
        val filter = RateLimitFilter(redisTemplate, objectMapper, capacity, windowSeconds)
        val registration = FilterRegistrationBean(filter)
        registration.addUrlPatterns("/api/*")
        // Java 의 setOrder(...) 는 Kotlin 에서 합성 프로퍼티로 접근한다.
        registration.order = Ordered.HIGHEST_PRECEDENCE
        return registration
    }
}
