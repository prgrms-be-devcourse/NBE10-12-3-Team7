package com.dongnemarket.global.security;

import com.dongnemarket.global.security.jwt.JwtAccessDeniedHandler;
import com.dongnemarket.global.security.jwt.JwtAuthenticationEntryPoint;
import com.dongnemarket.global.security.jwt.JwtAuthenticationFilter;
import com.dongnemarket.global.security.jwt.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 공통 보안 설정 (JWT, stateless). URL 권한 정책은 00-ai-common-rules.md §7 기준.
 * 팀장만 수정한다.
 */
@Configuration
public class SecurityConfig {

	private static final String[] SWAGGER_WHITELIST = {
			"/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/swagger-resources/**"
	};

	private final JwtTokenProvider jwtTokenProvider;
	private final JwtAuthenticationEntryPoint authenticationEntryPoint;
	private final JwtAccessDeniedHandler accessDeniedHandler;
	private final String[] corsAllowedOrigins;

	public SecurityConfig(JwtTokenProvider jwtTokenProvider,
						  JwtAuthenticationEntryPoint authenticationEntryPoint,
						  JwtAccessDeniedHandler accessDeniedHandler,
						  @Value("${cors.allowed-origins}") String[] corsAllowedOrigins) {
		this.jwtTokenProvider = jwtTokenProvider;
		this.authenticationEntryPoint = authenticationEntryPoint;
		this.accessDeniedHandler = accessDeniedHandler;
		this.corsAllowedOrigins = corsAllowedOrigins;
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						// Swagger 문서
						.requestMatchers(SWAGGER_WHITELIST).permitAll()
						// 헬스체크(배포/모니터링용,인증 불필요)
						.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
						// Prometheus 메트릭 스크레이프 (로컬 모니터링용, 인증 불필요 — 운영 반영 시 접근 제한 필요)
						.requestMatchers("/actuator/prometheus").permitAll()
						// WebSocket 핸드셰이크(HTTP GET Upgrade). 실제 인증은 STOMP CONNECT 시 JwtChannelInterceptor(U1-d)에서.
						.requestMatchers("/ws/**").permitAll()
						// 인증 불필요 (회원가입/로그인, 공개 조회)
						.requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login", "/api/auth/reissue", "/api/auth/email-verifications", "/api/auth/email-verifications/confirm", "/api/auth/password-resets", "/api/auth/password-resets/confirm").permitAll()
						// 소셜 로그인(카카오/구글). provider가 URL에 변수로 들어가지 않는 리터럴 경로만 허용한다.
						.requestMatchers(HttpMethod.POST, "/api/auth/oauth/kakao/authorization", "/api/auth/oauth/google/authorization", "/api/auth/oauth/kakao/login", "/api/auth/oauth/google/login").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/products", "/api/products/{productId}").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/products/images/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/regions").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/categories/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/products/{productId}/comments").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/members/{memberId}/manner-score").permitAll()

						// 관리자 전용
						.requestMatchers("/api/admin/**").hasRole("ADMIN")
						// 그 외 모든 요청은 인증 필요
						.anyRequest().authenticated()
				)
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler)
				)
				.addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
						UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}

	/**
	 * 프론트-백엔드가 다른 origin일 수 있어(소셜 로그인 도입으로 credentials 포함 요청이 늘어남) 도입.
	 * 정확한 origin만 허용하고(와일드카드 금지) credentials를 허용한다. 허용 origin은
	 * {@code cors.allowed-origins}(환경변수 {@code CORS_ALLOWED_ORIGINS}, 콤마 구분)로 환경별 관리.
	 * <p>공백/빈 값은 무시하고, {@code *}가 섞여 있으면 잘못된 배포 설정으로 보고 기동을 막는다
	 * (credentials 허용 상태에서 와일드카드 origin은 브라우저도 거부하지만, 설정 실수를 여기서 먼저 잡는다).
	 */
	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		List<String> allowedOrigins = Arrays.stream(corsAllowedOrigins)
				.map(String::trim)
				.filter(origin -> !origin.isEmpty())
				.toList();
		if (allowedOrigins.isEmpty()) {
			throw new IllegalStateException("cors.allowed-origins(CORS_ALLOWED_ORIGINS)가 비어 있습니다.");
		}
		if (allowedOrigins.contains("*")) {
			throw new IllegalStateException("cors.allowed-origins(CORS_ALLOWED_ORIGINS)에 와일드카드(*)는 허용되지 않습니다.");
		}

		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(allowedOrigins);
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("*"));
		configuration.setAllowCredentials(true);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	/** auth 도메인 로그인에서 사용할 수 있도록 노출 */
	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
		return configuration.getAuthenticationManager();
	}
}
