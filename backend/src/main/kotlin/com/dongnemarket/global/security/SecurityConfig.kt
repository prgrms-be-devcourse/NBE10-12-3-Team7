package com.dongnemarket.global.security

import com.dongnemarket.global.security.jwt.JwtAccessDeniedHandler
import com.dongnemarket.global.security.jwt.JwtAuthenticationEntryPoint
import com.dongnemarket.global.security.jwt.JwtAuthenticationFilter
import com.dongnemarket.global.security.jwt.JwtTokenProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * 공통 보안 설정 (JWT, stateless). URL 권한 정책은 00-ai-common-rules.md §7 기준.
 * 팀장만 수정한다.
 *
 * `@Value` 에 `@param:` 을 명시한 이유: 생성자 프로퍼티(`val`)에 붙은 어노테이션은
 * 현재 파라미터에만 적용되지만, Kotlin 2.2 는 "향후 필드에도 적용될 예정"이라고 경고한다.
 * 생성자 주입에서 원하는 대상은 파라미터이므로 명시해 의도를 고정한다
 * — JPA 어노테이션이 `@field:` 를 필요로 하는 것과 정반대 경우다.
 */
@Configuration
class SecurityConfig(
    private val jwtTokenProvider: JwtTokenProvider,
    private val authenticationEntryPoint: JwtAuthenticationEntryPoint,
    private val accessDeniedHandler: JwtAccessDeniedHandler,
    @param:Value("\${cors.allowed-origins}") private val corsAllowedOrigins: Array<String>,
) {
    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // Java 의 메서드 참조 `AbstractHttpConfigurer::disable` 은 람다 `{ it.disable() }` 로 옮긴다.
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    // Swagger 문서 — 배열을 가변인자 자리에 넘기므로 스프레드(*)가 필요하다.
                    .requestMatchers(*SWAGGER_WHITELIST)
                    .permitAll()
                    // 헬스체크(배포/모니터링용, 인증 불필요)
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    // Prometheus 메트릭 스크레이프 (로컬 모니터링용, 인증 불필요 — 운영 반영 시 접근 제한 필요)
                    .requestMatchers("/actuator/prometheus")
                    .permitAll()
                    // WebSocket 핸드셰이크(HTTP GET Upgrade). 실제 인증은 STOMP CONNECT 시 JwtChannelInterceptor 에서.
                    .requestMatchers("/ws/**")
                    .permitAll()
                    // 인증 불필요 (회원가입/로그인, 공개 조회)
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/auth/signup",
                        "/api/auth/login",
                        "/api/auth/reissue",
                        "/api/auth/email-verifications",
                        "/api/auth/email-verifications/confirm",
                        "/api/auth/password-resets",
                        "/api/auth/password-resets/confirm",
                    ).permitAll()
                    // 소셜 로그인(카카오/구글). provider가 URL에 변수로 들어가지 않는 리터럴 경로만 허용한다.
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/auth/oauth/kakao/authorization",
                        "/api/auth/oauth/google/authorization",
                        "/api/auth/oauth/kakao/login",
                        "/api/auth/oauth/google/login",
                    ).permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/{productId}")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/products/images/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/regions")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/categories/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/products/{productId}/comments")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/members/{memberId}/manner-score")
                    .permitAll()
                    // 관리자 전용
                    .requestMatchers("/api/admin/**")
                    .hasRole("ADMIN")
                    // 그 외 모든 요청은 인증 필요
                    .anyRequest()
                    .authenticated()
            }.exceptionHandling {
                it
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
            }.addFilterBefore(
                JwtAuthenticationFilter(jwtTokenProvider),
                UsernamePasswordAuthenticationFilter::class.java,
            )

        return http.build()
    }

    /**
     * 프론트-백엔드가 다른 origin일 수 있어(소셜 로그인 도입으로 credentials 포함 요청이 늘어남) 도입.
     * 정확한 origin만 허용하고(와일드카드 금지) credentials를 허용한다. 허용 origin은
     * `cors.allowed-origins`(환경변수 `CORS_ALLOWED_ORIGINS`, 콤마 구분)로 환경별 관리.
     *
     * 공백/빈 값은 무시하고, `*`가 섞여 있으면 잘못된 배포 설정으로 보고 기동을 막는다
     * (credentials 허용 상태에서 와일드카드 origin은 브라우저도 거부하지만, 설정 실수를 여기서 먼저 잡는다).
     */
    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        // Java 의 Arrays.stream(...).map(String::trim).filter(...).toList() 가
        // 배열 확장 함수 체인으로 그대로 대체된다(중간 스트림 객체가 없다).
        // 변수명을 origins 로 둔 이유: 아래 apply 블록 안에서 CorsConfiguration.allowedOrigins
        // 프로퍼티와 이름이 겹치면 어느 쪽인지 모호해진다.
        val origins =
            corsAllowedOrigins
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        // check(조건) { 메시지 } 는 조건이 false 일 때 IllegalStateException 을 던진다
        // — Java 의 `if (...) throw new IllegalStateException(...)` 와 같고, 메시지는 실패 시에만 만들어진다.
        check(origins.isNotEmpty()) {
            "cors.allowed-origins(CORS_ALLOWED_ORIGINS)가 비어 있습니다."
        }
        check(!origins.contains("*")) {
            "cors.allowed-origins(CORS_ALLOWED_ORIGINS)에 와일드카드(*)는 허용되지 않습니다."
        }

        // apply 는 수신 객체(this)를 그대로 반환하므로, 설정 대입을 한 블록에 모으고 결과를 바로 받는다.
        val configuration =
            CorsConfiguration().apply {
                allowedOrigins = origins
                allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                allowedHeaders = listOf("*")
                allowCredentials = true
            }

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    /** auth 도메인 로그인에서 사용할 수 있도록 노출 */
    @Bean
    fun authenticationManager(configuration: AuthenticationConfiguration): AuthenticationManager = configuration.authenticationManager

    companion object {
        private val SWAGGER_WHITELIST =
            arrayOf(
                "/swagger-ui/**",
                "/swagger-ui.html",
                "/v3/api-docs/**",
                "/swagger-resources/**",
            )
    }
}
