package com.dongnemarket.auth.config

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 카카오/구글 REST API 호출 전용 [WebClient]. 연결/응답/쓰기 타임아웃과 응답 최대 크기를 명시적으로
 * 둔다 — 제공자가 느리거나 무한정 응답을 흘려보내도 요청 스레드가 붙잡히지 않게 하기 위함이다.
 *
 * 이 WebClient 에는 재시도(retry)를 붙이지 않는다 — 인가 코드 교환은 1회용 작업이라 자동 재시도가
 * 오히려 코드를 낭비하거나 의도치 않은 재사용 오류를 유발할 수 있다.
 *
 * 주의: 이 애플리케이션에서 `reactor.netty.http.client`/
 * `org.springframework.web.reactive.function.client` 로거를 DEBUG 이상으로 올리지 말 것 —
 * wire 로깅에 Authorization 헤더(인가 코드 교환 시 client secret 등)와 응답 본문(access token)이 그대로 찍힌다.
 *
 * 전환 규칙 — **`@Bean` 메서드 이름 `oauthWebClient` 가 곧 빈 이름**이라 그대로 유지한다
 * (`KakaoOAuthClient`·`GoogleOAuthClient` 가 파라미터 이름으로 이 빈을 지목한다).
 * 기본값 파라미터나 `@JvmOverloads` 를 쓰지 않아 오버로드가 늘지 않는다.
 * primitive 파라미터(`int`·`long`)는 non-null 로 두어 원본 descriptor 를 그대로 만든다.
 */
@Configuration
class OAuthWebClientConfig {
    @Bean
    fun oauthWebClient(
        builder: WebClient.Builder,
        @Value("\${oauth.http.connect-timeout-millis}") connectTimeoutMillis: Int,
        @Value("\${oauth.http.response-timeout-millis}") responseTimeoutMillis: Long,
        @Value("\${oauth.http.max-in-memory-size-bytes}") maxInMemorySizeBytes: Int,
    ): WebClient {
        val httpClient =
            HttpClient
                .create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .responseTimeout(Duration.ofMillis(responseTimeoutMillis))
                .doOnConnected { connection ->
                    connection
                        .addHandlerLast(ReadTimeoutHandler(responseTimeoutMillis, TimeUnit.MILLISECONDS))
                        .addHandlerLast(WriteTimeoutHandler(responseTimeoutMillis, TimeUnit.MILLISECONDS))
                }

        return builder
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { configurer -> configurer.defaultCodecs().maxInMemorySize(maxInMemorySizeBytes) }
            .build()
    }
}
